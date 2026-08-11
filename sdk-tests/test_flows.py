"""End-to-end flows driven by the official stripe-python SDK.

If our response shapes were wrong, the SDK would fail to build its typed objects,
so these passing is direct evidence of wire compatibility with real Stripe.
"""
import urllib.error
import urllib.parse
import urllib.request

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


def test_refund_via_sdk():
    pi = stripe.PaymentIntent.create(
        amount=2000, currency="usd", payment_method="pm_card_visa", confirm=True
    )
    refund = stripe.Refund.create(charge=pi.latest_charge, amount=500)
    assert refund.object == "refund"
    assert refund.amount == 500
    assert refund.status == "succeeded"


def test_product_price_subscription_via_sdk():
    customer = stripe.Customer.create(email="sdk-sub@example.com")
    product = stripe.Product.create(name="SDK Plan")
    price = stripe.Price.create(
        product=product.id, currency="usd", unit_amount=1500, recurring={"interval": "month"}
    )
    sub = stripe.Subscription.create(
        customer=customer.id,
        items=[{"price": price.id}],
        default_payment_method="pm_card_visa",
    )
    assert sub.status == "active"
    assert sub.latest_invoice is not None

    invoice = stripe.Invoice.retrieve(sub.latest_invoice)
    assert invoice.status == "paid"
    assert invoice.total == 1500


def test_idempotency_key_via_sdk():
    a = stripe.Customer.create(email="idem-sdk@example.com", idempotency_key="sdk-key-1")
    b = stripe.Customer.create(email="idem-sdk@example.com", idempotency_key="sdk-key-1")
    assert a.id == b.id


def test_hosted_checkout_flow_via_sdk():
    """The SDK creates the session; a browser (urllib here) pays on the hosted page."""
    product = stripe.Product.create(name="SDK Checkout Plan")
    price = stripe.Price.create(
        product=product.id, currency="usd", unit_amount=900, recurring={"interval": "month"}
    )
    session = stripe.checkout.Session.create(
        mode="subscription",
        line_items=[{"price": price.id, "quantity": 1}],
        customer_email="checkout-sdk@example.com",
        client_reference_id="subscriber-7",
        success_url="https://example.com/account?checkout=done",
        cancel_url="https://example.com/upgrade",
    )
    assert session.id.startswith("cs_")
    assert session.status == "open"
    assert session.payment_status == "unpaid"
    assert session.client_reference_id == "subscriber-7"
    assert session.url.endswith(f"/checkout/{session.id}")

    # Press Pay the way a browser would, and refuse to follow the redirect.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args):
            return None

    req = urllib.request.Request(
        session.url + "/pay",
        data=urllib.parse.urlencode({"card_number": "4242424242424242"}).encode(),
        method="POST",
    )
    try:
        urllib.request.build_opener(NoRedirect).open(req)
        raise AssertionError("expected a redirect to success_url")
    except urllib.error.HTTPError as e:
        assert e.code == 303
        assert e.headers["Location"] == "https://example.com/account?checkout=done"

    # The session completed, and the SDK reads back the subscription it created.
    done = stripe.checkout.Session.retrieve(session.id)
    assert done.status == "complete"
    assert done.payment_status == "paid"
    assert done.customer.startswith("cus_")
    assert stripe.Subscription.retrieve(done.subscription).status == "active"

    events = stripe.Event.list(type="checkout.session.completed", limit=10)
    assert any(e.data.object["client_reference_id"] == "subscriber-7" for e in events.data)


def test_billing_portal_session_via_sdk():
    customer = stripe.Customer.create(email="portal-sdk@example.com")
    session = stripe.billing_portal.Session.create(
        customer=customer.id, return_url="https://example.com/account"
    )
    assert session.id.startswith("bps_")
    assert session.customer == customer.id
    assert session.url.endswith(f"/billing_portal/{session.id}")
