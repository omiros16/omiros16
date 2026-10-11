"""Telegram alerts. Never raises and never blocks the trading loop.

Messages are queued and sent by a background thread, so a slow or unreachable
Telegram cannot delay protective closes. The bot token is never written to the
logs (request exceptions include the URL, which contains the token).
"""

from __future__ import annotations

import logging
import queue
import threading
import time

import requests

log = logging.getLogger(__name__)


class Alerts:
    def __init__(self, token: str | None = None, chat_id: str | None = None, prefix: str = "") -> None:
        self.token = token
        self.chat_id = chat_id
        self.prefix = prefix
        self._recent: dict[str, float] = {}
        self._queue: queue.Queue[str | None] = queue.Queue(maxsize=500)
        self._worker: threading.Thread | None = None

    @property
    def enabled(self) -> bool:
        return bool(self.token and self.chat_id)

    def send(self, text: str, dedupe_seconds: float = 0.0) -> None:
        """Log and queue. With dedupe_seconds, the same text is sent at most once per window."""
        log.info("ALERT: %s", text.replace("\n", " | "))
        if dedupe_seconds:
            now = time.monotonic()
            if now - self._recent.get(text, -1e18) < dedupe_seconds:
                return
            self._recent[text] = now
        if not self.enabled:
            return
        if self._worker is None:
            self._worker = threading.Thread(target=self._run, name="telegram-alerts", daemon=True)
            self._worker.start()
        try:
            self._queue.put_nowait(f"{self.prefix}{text}")
        except queue.Full:
            log.warning("alert queue full, message dropped")

    def close(self, timeout: float = 10.0) -> None:
        """Flush queued messages (best effort) and stop the worker."""
        if self._worker is None:
            return
        try:
            self._queue.put(None, timeout=1)
        except queue.Full:
            return
        self._worker.join(timeout)

    def _run(self) -> None:
        while True:
            text = self._queue.get()
            if text is None:
                return
            self._post(text)

    def _post(self, text: str) -> None:
        try:
            resp = requests.post(
                f"https://api.telegram.org/bot{self.token}/sendMessage",
                json={"chat_id": self.chat_id, "text": text, "disable_web_page_preview": True},
                timeout=10,
            )
            if resp.status_code != 200:
                log.warning("Telegram error %s: %s", resp.status_code, self._redact(resp.text[:200]))
        except requests.RequestException as exc:
            log.warning("Telegram unreachable: %s", self._redact(f"{type(exc).__name__}: {exc}"))

    def _redact(self, text: str) -> str:
        return text.replace(self.token, "<token>") if self.token else text
