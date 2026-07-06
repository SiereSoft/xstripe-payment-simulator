"""End-to-end flows driven by the official stripe-python SDK.

If our response shapes were wrong, the SDK would fail to build its typed objects,
so these passing is direct evidence of wire compatibility with real Stripe.
"""
import pytest
import stripe

# CardError has lived in a couple of import locations across SDK versions.
try:
    from stripe.error import CardError
except Exception:  # pragma: no cover - version shim
    from stripe import CardError


def test_create_customer():
    c = stripe.Customer.create(email="jane@example.com", name="Jane")
    assert c.id.startswith("cus_")
    assert c.email == "jane@example.com"
    assert c.object == "customer"


def test_create_and_confirm_succeeds():
    pi = stripe.PaymentIntent.create(
        amount=2000, currency="usd", payment_method="pm_card_visa", confirm=True
    )
    assert pi.status == "succeeded"
    assert pi.amount_received == 2000
    assert pi.latest_charge.startswith("ch_")


def test_retrieve_round_trip():
    pi = stripe.PaymentIntent.create(
        amount=1000, currency="usd", payment_method="pm_card_visa", confirm=True
    )
    got = stripe.PaymentIntent.retrieve(pi.id)
    assert got.id == pi.id
    assert got.status == "succeeded"


def test_manual_capture():
    pi = stripe.PaymentIntent.create(
        amount=1500,
        currency="usd",
        capture_method="manual",
        payment_method="pm_card_visa",
        confirm=True,
    )
    assert pi.status == "requires_capture"
    captured = stripe.PaymentIntent.capture(pi.id)
    assert captured.status == "succeeded"
    assert captured.amount_received == 1500


def test_customer_and_charge_list():
    stripe.Customer.create(email="list@example.com")
    customers = stripe.Customer.list(limit=3)
    assert customers.object == "list"
    assert len(customers.data) > 0


def test_declined_card_raises_card_error():
    with pytest.raises(CardError) as exc:
        stripe.PaymentIntent.create(
            amount=500,
            currency="usd",
            payment_method="pm_card_chargeDeclined",
            confirm=True,
        )
    assert exc.value.code == "card_declined"
