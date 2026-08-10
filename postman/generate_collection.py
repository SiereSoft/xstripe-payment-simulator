#!/usr/bin/env python3
"""Generate the Postman collection for fake-stripe.

Single source of truth for the collection. When an endpoint is added or changed,
edit the ITEMS below and re-run:

    python3 postman/generate_collection.py

It writes `fake-stripe.postman_collection.json` next to this script. Keeping the
collection generated (rather than hand-edited) is what makes "update it every
time" cheap and reliable.
"""
import json
import os

COLLECTION_NAME = "fake-stripe"
SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"


def url(path, query=None):
    raw = "{{baseUrl}}" + path
    if query:
        raw += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    u = {
        "raw": raw,
        "host": ["{{baseUrl}}"],
        "path": [p for p in path.strip("/").split("/")],
    }
    if query:
        u["query"] = [{"key": k, "value": str(v)} for k, v in query.items()]
    return u


def body(fields):
    return {
        "mode": "urlencoded",
        "urlencoded": [{"key": k, "value": str(v)} for k, v in fields],
    }


def script(lines):
    return {"listen": "test", "script": {"type": "text/javascript", "exec": lines}}


def capture(var, source):
    return [
        "const d = pm.response.json();",
        f"if (d.{source}) pm.collectionVariables.set('{var}', d.{source});",
        "pm.test('2xx', () => pm.expect(pm.response.code).to.be.oneOf([200]));",
    ]


def req(name, method, path, fields=None, query=None, tests=None, no_auth=False, headers=None):
    hdrs = [{"key": k, "value": v} for k, v in (headers or [])]
    request = {"method": method, "header": hdrs, "url": url(path, query)}
    if fields is not None:
        request["body"] = body(fields)
    if no_auth:
        request["auth"] = {"type": "noauth"}
    item = {"name": name, "request": request}
    if tests:
        item["event"] = [script(tests)]
    return item


def folder(name, items):
    return {"name": name, "item": items}


ITEMS = [
    folder("Admin", [
        req("Liveness", "GET", "/healthz", no_auth=True),
        req("World summary", "GET", "/v1/admin/health", no_auth=True),
        req("Privileged full-state export", "GET", "/v1/admin/state", no_auth=True,
            headers=[("X-Siere-Control-Token", "{{controlToken}}")]),
        req("Reset (seed=1)", "POST", "/v1/admin/reset", query={"seed": 1}, no_auth=True),
        req("Reset duplicate-payments task", "POST", "/v1/admin/reset",
            query={"seed": 42, "scenario": "duplicate_payments"}, no_auth=True),
    ]),
    folder("Customers", [
        req("Create customer", "POST", "/v1/customers",
            fields=[("email", "jane@example.com"), ("name", "Jane Doe"), ("metadata[order]", "A1")],
            tests=capture("customerId", "id")),
        req("Retrieve customer", "GET", "/v1/customers/{{customerId}}"),
        req("Update customer", "POST", "/v1/customers/{{customerId}}",
            fields=[("description", "VIP customer")]),
        req("List customers", "GET", "/v1/customers", query={"limit": 10}),
        req("List customer's payment methods", "GET", "/v1/customers/{{customerId}}/payment_methods"),
        req("Delete customer", "DELETE", "/v1/customers/{{customerId}}"),
    ]),
    folder("PaymentMethods", [
        req("Create payment method (card)", "POST", "/v1/payment_methods",
            fields=[("type", "card"), ("card[number]", "4242424242424242"),
                    ("card[exp_month]", "12"), ("card[exp_year]", "2034"), ("card[cvc]", "123")],
            tests=capture("paymentMethodId", "id")),
        req("Retrieve payment method", "GET", "/v1/payment_methods/{{paymentMethodId}}"),
        req("Update payment method", "POST", "/v1/payment_methods/{{paymentMethodId}}",
            fields=[("billing_details[name]", "Jane Doe")]),
        req("Attach to customer", "POST", "/v1/payment_methods/{{paymentMethodId}}/attach",
            fields=[("customer", "{{customerId}}")]),
        req("Detach from customer", "POST", "/v1/payment_methods/{{paymentMethodId}}/detach"),
        req("List payment methods", "GET", "/v1/payment_methods", query={"customer": "{{customerId}}", "type": "card"}),
    ]),
    folder("PaymentIntents", [
        req("Create (unconfirmed)", "POST", "/v1/payment_intents",
            fields=[("amount", "2000"), ("currency", "usd"), ("customer", "{{customerId}}")],
            tests=capture("paymentIntentId", "id")),
        req("Create & confirm (succeeds)", "POST", "/v1/payment_intents",
            fields=[("amount", "2000"), ("currency", "usd"), ("payment_method", "pm_card_visa"), ("confirm", "true")],
            tests=[
                "const d = pm.response.json();",
                "if (d.id) pm.collectionVariables.set('paymentIntentId', d.id);",
                "if (d.latest_charge) pm.collectionVariables.set('chargeId', d.latest_charge);",
                "pm.test('succeeded', () => pm.expect(d.status).to.eql('succeeded'));",
            ]),
        req("Create & confirm (declined)", "POST", "/v1/payment_intents",
            fields=[("amount", "500"), ("currency", "usd"), ("payment_method", "pm_card_chargeDeclined"), ("confirm", "true")],
            tests=[
                "pm.test('402 card_error', () => {",
                "  pm.expect(pm.response.code).to.eql(402);",
                "  pm.expect(pm.response.json().error.type).to.eql('card_error');",
                "});",
            ]),
        req("Retrieve", "GET", "/v1/payment_intents/{{paymentIntentId}}"),
        req("Confirm", "POST", "/v1/payment_intents/{{paymentIntentId}}/confirm",
            fields=[("payment_method", "pm_card_visa")]),
        req("Capture (manual flow)", "POST", "/v1/payment_intents/{{paymentIntentId}}/capture"),
        req("Cancel", "POST", "/v1/payment_intents/{{paymentIntentId}}/cancel",
            fields=[("cancellation_reason", "requested_by_customer")]),
        req("Update", "POST", "/v1/payment_intents/{{paymentIntentId}}",
            fields=[("description", "Order A1")]),
        req("List", "GET", "/v1/payment_intents", query={"limit": 10}),
    ]),
    folder("Charges", [
        req("Retrieve", "GET", "/v1/charges/{{chargeId}}"),
        req("Update", "POST", "/v1/charges/{{chargeId}}", fields=[("metadata[reviewed]", "true")]),
        req("List", "GET", "/v1/charges", query={"limit": 10}),
    ]),
    folder("Refunds", [
        req("Create refund (partial)", "POST", "/v1/refunds",
            fields=[("charge", "{{chargeId}}"), ("amount", "500")],
            tests=capture("refundId", "id")),
        req("Retrieve refund", "GET", "/v1/refunds/{{refundId}}"),
        req("List refunds", "GET", "/v1/refunds", query={"charge": "{{chargeId}}"}),
    ]),
    folder("Products", [
        req("Create product", "POST", "/v1/products",
            fields=[("name", "Gold Plan")], tests=capture("productId", "id")),
        req("Retrieve product", "GET", "/v1/products/{{productId}}"),
        req("Update product", "POST", "/v1/products/{{productId}}", fields=[("description", "Best plan")]),
        req("List products", "GET", "/v1/products", query={"limit": 10}),
        req("Delete product", "DELETE", "/v1/products/{{productId}}"),
    ]),
    folder("Prices", [
        req("Create price (recurring)", "POST", "/v1/prices",
            fields=[("product", "{{productId}}"), ("currency", "usd"), ("unit_amount", "2500"), ("recurring[interval]", "month")],
            tests=capture("priceId", "id")),
        req("Retrieve price", "GET", "/v1/prices/{{priceId}}"),
        req("List prices", "GET", "/v1/prices", query={"product": "{{productId}}"}),
    ]),
    folder("Subscriptions", [
        req("Create subscription", "POST", "/v1/subscriptions",
            fields=[("customer", "{{customerId}}"), ("items[0][price]", "{{priceId}}"), ("default_payment_method", "{{paymentMethodId}}")],
            tests=[
                "const d = pm.response.json();",
                "if (d.id) pm.collectionVariables.set('subscriptionId', d.id);",
                "if (d.latest_invoice) pm.collectionVariables.set('invoiceId', d.latest_invoice);",
                "pm.test('active', () => pm.expect(d.status).to.eql('active'));",
            ]),
        req("Retrieve subscription", "GET", "/v1/subscriptions/{{subscriptionId}}"),
        req("Update (cancel at period end)", "POST", "/v1/subscriptions/{{subscriptionId}}",
            fields=[("cancel_at_period_end", "true")]),
        req("List subscriptions", "GET", "/v1/subscriptions", query={"customer": "{{customerId}}"}),
        req("Renew into next period (admin)", "POST", "/v1/admin/subscriptions/{{subscriptionId}}/renew",
            no_auth=True,
            tests=[
                "const d = pm.response.json();",
                "if (d.latest_invoice) pm.collectionVariables.set('invoiceId', d.latest_invoice);",
                "pm.test('active or past_due', () => pm.expect(d.status).to.be.oneOf(['active', 'past_due']));",
            ]),
        req("Cancel now", "DELETE", "/v1/subscriptions/{{subscriptionId}}"),
    ]),
    folder("Checkout", [
        req("Create session (subscription)", "POST", "/v1/checkout/sessions",
            fields=[("mode", "subscription"), ("line_items[0][price]", "{{priceId}}"),
                    ("line_items[0][quantity]", "1"), ("customer_email", "jane@example.com"),
                    ("client_reference_id", "your-user-id"),
                    ("success_url", "https://example.com/account?checkout=done"),
                    ("cancel_url", "https://example.com/upgrade")],
            tests=[
                "const d = pm.response.json();",
                "if (d.id) pm.collectionVariables.set('checkoutSessionId', d.id);",
                "pm.test('has a hosted url', () => pm.expect(d.url).to.be.a('string'));",
                "console.log('Open this in a browser to pay: ' + d.url);",
            ]),
        req("Retrieve session", "GET", "/v1/checkout/sessions/{{checkoutSessionId}}"),
        req("List sessions", "GET", "/v1/checkout/sessions", query={"limit": 10}),
        req("Expire session", "POST", "/v1/checkout/sessions/{{checkoutSessionId}}/expire"),
        # The hosted page is browser-facing and takes no API key — this is what pressing Pay does.
        req("Pay on the hosted page", "POST", "/checkout/{{checkoutSessionId}}/pay",
            fields=[("card_number", "4242424242424242")], no_auth=True,
            tests=[
                "pm.test('redirects to success_url', () => pm.expect(pm.response.code).to.eql(303));",
            ]),
    ]),
    folder("Customer portal", [
        req("Create portal session", "POST", "/v1/billing_portal/sessions",
            fields=[("customer", "{{customerId}}"), ("return_url", "https://example.com/account")],
            tests=[
                "const d = pm.response.json();",
                "if (d.id) pm.collectionVariables.set('portalSessionId', d.id);",
                "console.log('Open this in a browser to manage billing: ' + d.url);",
            ]),
        req("Cancel a plan from the portal", "POST",
            "/billing_portal/{{portalSessionId}}/subscriptions/{{subscriptionId}}/cancel", no_auth=True),
        req("Renew it again", "POST",
            "/billing_portal/{{portalSessionId}}/subscriptions/{{subscriptionId}}/resume", no_auth=True),
    ]),
    folder("Invoices", [
        req("Retrieve invoice", "GET", "/v1/invoices/{{invoiceId}}"),
        req("List invoices", "GET", "/v1/invoices", query={"customer": "{{customerId}}"}),
        req("Pay invoice", "POST", "/v1/invoices/{{invoiceId}}/pay"),
        req("Void invoice", "POST", "/v1/invoices/{{invoiceId}}/void"),
    ]),
    folder("Events", [
        req("List events", "GET", "/v1/events", query={"limit": 10}),
        req("Retrieve event", "GET", "/v1/events/{{eventId}}"),
    ]),
    folder("Webhooks (admin)", [
        req("Set webhook URL + secret", "POST", "/v1/admin/webhook",
            fields=[("url", "https://example.com/webhook"), ("secret", "whsec_test")], no_auth=True),
        req("Get webhook config", "GET", "/v1/admin/webhook", no_auth=True),
    ]),
    folder("Idempotency", [
        req("Create customer with Idempotency-Key", "POST", "/v1/customers",
            fields=[("email", "idem@example.com")],
            headers=[("Idempotency-Key", "demo-key-001")]),
    ]),
]

COLLECTION = {
    "info": {
        "name": COLLECTION_NAME,
        "description": (
            "Stateful Stripe payments-core simulator. Set {{baseUrl}} and {{apiKey}} "
            "(any sk_... value works). Requests are form-encoded like real Stripe. "
            "IDs from create calls are captured into collection variables so folders chain."
        ),
        "schema": SCHEMA,
    },
    "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{apiKey}}", "type": "string"}]},
    "event": [],
    "variable": [
        {"key": "baseUrl", "value": "http://localhost:12111"},
        {"key": "apiKey", "value": "sk_test_123"},
        {"key": "controlToken", "value": "gym_control_local"},
        {"key": "customerId", "value": ""},
        {"key": "paymentMethodId", "value": ""},
        {"key": "paymentIntentId", "value": ""},
        {"key": "chargeId", "value": ""},
        {"key": "refundId", "value": ""},
        {"key": "productId", "value": ""},
        {"key": "priceId", "value": ""},
        {"key": "subscriptionId", "value": ""},
        {"key": "invoiceId", "value": ""},
        {"key": "checkoutSessionId", "value": ""},
        {"key": "portalSessionId", "value": ""},
        {"key": "eventId", "value": ""},
    ],
    "item": ITEMS,
}


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake-stripe.postman_collection.json")
    with open(out, "w") as f:
        json.dump(COLLECTION, f, indent=2)
        f.write("\n")
    n = sum(len(folder["item"]) for folder in ITEMS)
    print(f"Wrote {out} ({len(ITEMS)} folders, {n} requests)")


if __name__ == "__main__":
    main()
