"""Connection to a running MetaTrader 5 terminal via the official `MetaTrader5` package.

Windows only: the package talks to the MT5 terminal installed on the same
machine (run it on a Windows VPS, logged in to your FTMO / E8 account, with
"Algo Trading" switched on).

Times returned by MT5 (bars, ticks, positions) are broker *server* time,
expressed as seconds since the epoch. They are kept as naive timestamps.
"""

from __future__ import annotations

import logging
from types import ModuleType

import numpy as np
import pandas as pd

log = logging.getLogger(__name__)

TIMEFRAMES = ("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1")


class MT5Error(RuntimeError):
    pass


class MT5Client:
    def __init__(
        self,
        login: int | None = None,
        password: str | None = None,
        server: str | None = None,
        path: str | None = None,
        mt5_module: ModuleType | None = None,
    ) -> None:
        self.login = login
        self.password = password
        self.server = server
        self.path = path
        self._mt5 = mt5_module
        self.connected = False
        # The account the bot is meant to trade: the configured login, or the
        # account found at the first successful connection.
        self.expected_login: int | None = int(login) if login else None

    @property
    def mt5(self) -> ModuleType:
        if self._mt5 is None:
            try:
                import MetaTrader5  # type: ignore[import-not-found]
            except ImportError as exc:
                raise MT5Error(
                    "The MetaTrader5 package is missing. It only exists for Windows: pip install MetaTrader5"
                ) from exc
            self._mt5 = MetaTrader5
        return self._mt5

    def connect(self) -> None:
        mt5 = self.mt5
        kwargs: dict = {}
        if self.path:
            kwargs["path"] = self.path
        if self.login:
            kwargs.update(login=int(self.login), password=self.password or "", server=self.server or "")
        if not mt5.initialize(**kwargs):
            raise MT5Error(f"MT5 initialize failed: {mt5.last_error()}")
        info = mt5.terminal_info()
        if info is not None and not info.trade_allowed:
            log.warning("Algo Trading is switched OFF in the MT5 terminal: orders will be rejected")
        account = mt5.account_info()
        if account is None:
            raise MT5Error(f"not logged in to a trading account: {mt5.last_error()}")
        if self.expected_login is None:
            self.expected_login = int(account.login)
        self._check_account(account)
        self.connected = True
        log.info("Connected to MT5: account %s on %s (%s)", account.login, account.server, account.currency)

    def ensure(self) -> None:
        """Reconnect if needed and make sure the terminal is usable and on the right account.

        Raises MT5Error (instead of trading) when the terminal has lost its trade
        server connection or has been switched to another account: every
        MetaTrader5 call works on whatever account the terminal shows right now.
        """
        info = self.mt5.terminal_info() if self.connected else None
        if info is None:
            self.connected = False
            self.connect()
            info = self.mt5.terminal_info()
        if info is not None and not getattr(info, "connected", True):
            raise MT5Error("the MT5 terminal is not connected to the trade server")
        self._check_account(self.mt5.account_info())

    def _check_account(self, account) -> None:
        if account is None:
            raise MT5Error(f"not logged in to a trading account: {self.mt5.last_error()}")
        if self.expected_login is not None and int(account.login) != self.expected_login:
            raise MT5Error(
                f"the MT5 terminal is logged into account {account.login}, not {self.expected_login}: "
                "refusing to trade until it is switched back"
            )

    def shutdown(self) -> None:
        if self._mt5 is not None and self.connected:
            self._mt5.shutdown()
        self.connected = False

    def timeframe(self, tf: str) -> int:
        tf = tf.upper()
        if tf not in TIMEFRAMES:
            raise ValueError(f"timeframe must be one of {TIMEFRAMES}")
        return getattr(self.mt5, f"TIMEFRAME_{tf}")

    def select(self, symbol: str) -> None:
        if not self.mt5.symbol_select(symbol, True):
            raise MT5Error(f"symbol {symbol!r} not available: {self.mt5.last_error()}")


def rates_to_frame(rates: np.ndarray | None) -> pd.DataFrame:
    if rates is None or len(rates) == 0:
        return pd.DataFrame(columns=["open", "high", "low", "close", "volume"])
    df = pd.DataFrame(rates)
    df.index = pd.DatetimeIndex(pd.to_datetime(df["time"], unit="s"), name="time")
    df = df.rename(columns={"tick_volume": "volume"})
    return df[["open", "high", "low", "close", "volume"]].astype(float)
