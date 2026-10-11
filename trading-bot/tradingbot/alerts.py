"""Telegram alerts. Never raises: a failed alert must not stop the bot."""

from __future__ import annotations

import logging
import time

import requests

log = logging.getLogger(__name__)


class Alerts:
    def __init__(self, token: str | None = None, chat_id: str | None = None, prefix: str = "") -> None:
        self.token = token
        self.chat_id = chat_id
        self.prefix = prefix
        self._recent: dict[str, float] = {}

    @property
    def enabled(self) -> bool:
        return bool(self.token and self.chat_id)

    def send(self, text: str, dedupe_seconds: float = 0.0) -> None:
        """Log and send. With dedupe_seconds, the same text is sent at most once per window."""
        log.info("ALERT: %s", text.replace("\n", " | "))
        if dedupe_seconds:
            now = time.monotonic()
            if now - self._recent.get(text, -1e18) < dedupe_seconds:
                return
            self._recent[text] = now
        if not self.enabled:
            return
        try:
            resp = requests.post(
                f"https://api.telegram.org/bot{self.token}/sendMessage",
                json={"chat_id": self.chat_id, "text": f"{self.prefix}{text}", "disable_web_page_preview": True},
                timeout=10,
            )
            if resp.status_code != 200:
                log.warning("Telegram error %s: %s", resp.status_code, resp.text[:200])
        except requests.RequestException as exc:
            log.warning("Telegram unreachable: %s", exc)
