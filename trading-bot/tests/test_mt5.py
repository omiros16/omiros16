from types import SimpleNamespace

import pytest

from tradingbot.brokers.base import BrokerError
from tradingbot.brokers.mt5_broker import MT5Broker
from tradingbot.data.feeds import MT5Feed
from tradingbot.models import Position, Side
from tradingbot.mt5_client import MT5Client


@pytest.fixture
def broker(fake_mt5):
    client = MT5Client(login=1, password="x", server="Fake", mt5_module=fake_mt5)
    client.connect()
    return MT5Broker(client, deviation_points=15, retry_delay=0)


def test_spec_value_per_point(broker):
    spec = broker.spec("US100.cash")
    assert spec.value_per_point == pytest.approx(10.0)  # 0.1 per 0.01 tick
    assert spec.volume_step == 0.01


def test_open_sends_market_order_with_stops(broker, fake_mt5):
    pos = broker.open("US100.cash", Side.LONG, 1.25, 95.123, 120.0, 26101101, "us100_trend Ελληνικά")
    req = fake_mt5.requests[-1]
    assert req["action"] == fake_mt5.TRADE_ACTION_DEAL
    assert req["type"] == fake_mt5.ORDER_TYPE_BUY
    assert req["price"] == 101.0  # ask
    assert req["sl"] == 95.12 and req["tp"] == 120.0
    assert req["magic"] == 26101101 and req["deviation"] == 15
    assert req["type_filling"] == fake_mt5.ORDER_FILLING_IOC  # filling_mode bit 2
    assert req["comment"].isascii() and len(req["comment"]) <= 31
    assert pos.ticket == 555 and pos.volume == 1.25


def test_short_close_buys_at_ask(broker, fake_mt5):
    pos = Position("US500.cash", Side.SHORT, 2.0, 100.0, None, ticket=42, magic=7)
    fill = broker.close(pos, "exit")
    req = fake_mt5.requests[-1]
    assert req["type"] == fake_mt5.ORDER_TYPE_BUY and req["position"] == 42 and req["price"] == 101.0
    assert fill.price == 101.0 and fill.volume == 2.0


def test_modify_uses_sltp_action(broker, fake_mt5):
    broker.modify(Position("US100.cash", Side.LONG, 1.0, 100.0, None, ticket=9, magic=7), 97.555, None)
    req = fake_mt5.requests[-1]
    assert req["action"] == fake_mt5.TRADE_ACTION_SLTP and req["sl"] == 97.56 and req["tp"] == 0.0


def test_requote_is_retried_and_counted(broker, fake_mt5):
    fake_mt5.retcodes = [10004, 10004]
    broker.open("US100.cash", Side.SHORT, 1.0, 110.0, None, 1, "x")
    assert len(fake_mt5.requests) == 3
    assert broker.requests_sent == 3


def test_rejection_raises(broker, fake_mt5):
    fake_mt5.retcodes = [10016]  # invalid stops
    with pytest.raises(BrokerError, match="10016"):
        broker.open("US100.cash", Side.LONG, 1.0, 100.9, None, 1, "x")


def test_positions_are_filtered_by_magic(broker, fake_mt5):
    fake_mt5.open_positions = [
        SimpleNamespace(ticket=1, symbol="US100.cash", type=0, volume=1.0, price_open=100.0, sl=95.0, tp=0.0,
                        magic=7, comment="", swap=-1.0, time=1_700_000_000),
        SimpleNamespace(ticket=2, symbol="US100.cash", type=1, volume=1.0, price_open=100.0, sl=0.0, tp=0.0,
                        magic=99, comment="manual", swap=0.0, time=1_700_000_000),
    ]
    mine = broker.positions(magic=7)
    assert [p.ticket for p in mine] == [1]
    assert mine[0].side is Side.LONG and mine[0].take_profit is None and mine[0].stop_loss == 95.0
    assert broker.positions()[1].side is Side.SHORT


def test_feed_skips_the_forming_bar(fake_mt5):
    client = MT5Client(mt5_module=fake_mt5)
    client.connect()
    bars = MT5Feed(client).bars("US100.cash", "D1", 50)
    assert fake_mt5.rates_calls[-1] == ("US100.cash", fake_mt5.TIMEFRAME_D1, 1, 50)
    assert list(bars.columns) == ["open", "high", "low", "close", "volume"] and len(bars) == 50
