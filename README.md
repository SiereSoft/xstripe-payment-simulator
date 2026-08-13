# Siere Payments Gym

**A stateful, Stripe-compatible payments API you can run locally — for building, testing, and training AI agents against a payment backend that actually remembers what happened.**

[![CI](https://github.com/SiereSoft/xstripe-payment-simulator/actions/workflows/ci.yml/badge.svg)](https://github.com/SiereSoft/xstripe-payment-simulator/actions/workflows/ci.yml)
![license](https://img.shields.io/badge/license-Apache--2.0-blue)
![stack](https://img.shields.io/badge/Kotlin-Ktor-7F52FF)
![run](https://img.shields.io/badge/run-docker%20compose%20up-2496ED)

> An open agent-environment from [**Siere Soft**](https://sieresoft.com) — a European studio building verifiable simulated worlds for AI. *Not affiliated with, or endorsed by, Stripe, Inc. — see [NOTICE](NOTICE).*

It imitates Stripe's HTTP API closely enough that **Stripe's own client libraries — or an autonomous agent — drive it unmodified**, while remembering state across requests and restarts and re-creating an identical world from a seed. That statefulness is what makes it a training/evaluation *environment* rather than just a mock.

It covers the payments core end to end — the PaymentIntent status state machine (the hard, valuable part), refunds, idempotency, the product/price catalog, subscriptions with proration, invoices, and signed webhooks — plus deterministic seeding and Stripe-shaped error envelopes. See [Roadmap](#roadmap) for what's next.

> ⚠️ **Local development and testing only.** This is a simulator: it processes no real
> payments, its authentication is simulated, and it is not hardened for exposure to
> untrusted networks. **Never enter a real card number.** See [DISCLAIMER](DISCLAIMER.md).

---

## Why this exists

Real Stripe can't be used for training: it needs the internet, a real account, and it isn't reproducible. `stripe-mock` (Stripe's own fake) is stateless — create a customer and it's gone on the next call. This simulator keeps the realism of Stripe's wire format **and** adds the statefulness training needs: create a customer, and it's still there later; seed the world with a number, and you get the exact same starting state every time.

## Why not just use stripe-mock?

`stripe-mock` is Stripe's official mock server, generated from their OpenAPI spec. It returns *schema-valid* responses for the entire API — but it is **stateless and has no behavior**: create a customer and it's forgotten instantly; a PaymentIntent never really transitions; the card number doesn't cause a decline; a charge isn't linked to the customer you made. It's a *schema oracle*, not a *world*.

Training or evaluating an agent needs a world — the server must remember what happened, enforce the rules, and let actions have consequences a checker can inspect afterward. That statefulness is exactly what `stripe-mock` omits by design, and exactly what this simulator adds.

|  | stripe-mock | fake-stripe |
|---|---|---|
| Remembers what you created | No | **Yes** |
| Real PaymentIntent state machine | No | **Yes** |
| Test cards drive outcomes | No | **Yes** |
| Object relationships | No | **Yes** |
| Deterministic seeded world | No | **Yes** |
| Endpoint coverage | Entire API | ~25–30 core |

So we don't compete with `stripe-mock` — we **use it as a validation oracle**, diffing our response *shapes* against it (see Roadmap).

---

## As an agent environment

The loop that makes this a *gym* rather than a mock:

1. **Seed** a known world — `POST /v1/admin/reset?seed=N&scenario=duplicate_payments&episode_id=run-123` yields the same task-ready customers, cards, and history every time.
2. **Act** — point an agent (via the Stripe SDKs, an MCP tool layer, or raw HTTP) at the API and let it work: create a customer, take a payment, refund the smaller of two charges, upgrade a subscription…
3. **Verify** — use the privileged, redacted `GET /v1/admin/state` export to compare the complete world before and after. Because the world is deterministic and stateful, a checker can assert *exactly* what should have changed — and nothing else.

Packaged task definitions + automatic checkers and a worked training/eval example are on the [roadmap](#roadmap); the primitives they need — deterministic seeding, full state transitions, signed webhooks — are already here.

---

## Quick start

### With Docker (one command)

```bash
docker compose up --build
# Actor/provider API: http://localhost:12111
# Host-only controller API: http://localhost:12112
```

### Without Docker (JDK 11+)

```bash
FAKE_STRIPE_CONTROL_TOKEN=gym_control_local ./gradlew run
# Actor/provider API: http://localhost:12111
# Loopback-only controller API: http://localhost:12112
```

### Smoke test

```bash
BASE=http://localhost:12111
CONTROL=http://localhost:12112
CONTROL_TOKEN="X-Siere-Control-Token: gym_control_local"
KEY="Authorization: Bearer sk_test_123"   # any sk_... value works

# What's in the seeded world? (controller path + controller credential)
curl -s $CONTROL/v1/admin/health -H "$CONTROL_TOKEN"

# Create a customer and take a payment (business endpoints need a key)
CUS=$(curl -s -X POST $BASE/v1/customers -H "$KEY" -d email=jane@example.com | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
curl -s -X POST $BASE/v1/payment_intents -H "$KEY" \
  -d amount=2000 -d currency=usd -d customer=$CUS \
  -d payment_method=pm_card_visa -d confirm=true
# -> { ... "status": "succeeded", "amount_received": 2000, "latest_charge": "ch_..." }
```

---

## Configuration

All configuration is via environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `12111` | HTTP port |
| `HOST` | `127.0.0.1` | Bind address. Loopback by default — the actor API accepts any test key, so widening this exposes the whole world to your network. Docker sets `0.0.0.0` inside the container and publishes the port instead. |
| `CONTROL_PORT` | `12112` | Gym controller HTTP port; must differ from `PORT` |
| `CONTROL_HOST` | `127.0.0.1` | Controller bind address; keep loopback-only outside isolated container networks |
| `FAKE_STRIPE_SEED` | `1` | Seed for the initial world (only used when no snapshot exists) |
| `FAKE_STRIPE_DATA` | `data/state.json` | Where the state snapshot is written |
| `FAKE_STRIPE_CLOCK_MODE` | `free` | Initial clock mode when no snapshot exists: `free` follows wall time; `manual` starts at deterministic seeded time |
| `FAKE_STRIPE_CONTROL_TOKEN` | _(disabled)_ | Enables controller endpoints; callers must send the same value in `X-Siere-Control-Token` |
| `FAKE_STRIPE_WEBHOOK_URL` | _(none)_ | If set, signed events are POSTed here (also settable at runtime via `/v1/admin/webhook`) |
| `FAKE_STRIPE_WEBHOOK_SECRET` | `whsec_test` | Secret used to sign webhook payloads |
| `FAKE_STRIPE_PUBLIC_URL` | _(request host)_ | Base URL put in hosted checkout/portal `url`s — set it when a browser reaches the simulator at a different address than the API caller does |

### Authentication

Every `/v1` business endpoint requires an API key, like real Stripe — send `Authorization: Bearer sk_test_...`. Any test-mode value is accepted (this is a fake; we don't validate which key); a **missing** key returns `401 authentication_error`. The official SDKs send the business API key automatically.

**Live-mode keys are refused.** `sk_live_`, `rk_live_`, and `pk_live_` return `401` with an explanation. This server is not Stripe and must never receive a production credential — if you sent one by accident, rotate it.

Gym lifecycle and verifier endpoints run on a separate controller listener. They are disabled unless `FAKE_STRIPE_CONTROL_TOKEN` is configured and return `403` unless the caller supplies that token in `X-Siere-Control-Token`. Never expose the controller URL or token to the system under test.

---

## What it imitates (endpoints)

Requests are **`application/x-www-form-urlencoded`** with bracket notation (`metadata[key]=v`, `expand[]=customer`) — exactly like real Stripe. Responses are JSON in Stripe's object shapes. Every list supports `limit`, `starting_after`, `ending_before`; most objects support `expand[]`.

**Customers** — `POST/GET/POST(update)/DELETE /v1/customers[/{id}]`, `GET /v1/customers`, `GET /v1/customers/{id}/payment_methods`

**PaymentMethods** — `POST/GET/POST(update) /v1/payment_methods[/{id}]`, `POST .../attach`, `POST .../detach`, `GET /v1/payment_methods?customer=…`

**PaymentIntents** — `POST/GET/POST(update) /v1/payment_intents[/{id}]`, `POST .../confirm`, `POST .../capture`, `POST .../cancel`, `GET /v1/payment_intents`

**Charges** — `GET/POST(update) /v1/charges/{id}`, `GET /v1/charges`

**Refunds** — `POST/GET/POST(update) /v1/refunds[/{id}]`, `POST /v1/charges/{id}/refunds`, `GET /v1/refunds`

**Products** — `POST/GET/POST(update)/DELETE /v1/products[/{id}]`, `GET /v1/products`

**Prices** — `POST/GET/POST(update) /v1/prices[/{id}]`, `GET /v1/prices`

**Subscriptions** — `POST/GET/POST(update)/DELETE /v1/subscriptions[/{id}]`, `GET /v1/subscriptions`

**Invoices** — `GET/POST(update) /v1/invoices/{id}`, `POST .../pay`, `POST .../void`, `GET /v1/invoices`

**Checkout** — `POST/GET /v1/checkout/sessions[/{id}]`, `POST .../expire`, `GET /v1/checkout/sessions` — plus the **hosted page** the session's `url` points at (see below)

**Customer portal** — `POST /v1/billing_portal/sessions` — plus its hosted page

**Events** — `GET /v1/events/{id}`, `GET /v1/events`; lists can be filtered with `episode_id`

**Controller (non-Stripe, port 12112)** — `GET /v1/admin/health`, `POST /v1/admin/reset?seed=N[&scenario=ID][&clock_mode=free|manual]`, `GET|POST /v1/admin/webhook`, `POST /v1/admin/subscriptions/{id}/renew`, `GET /v1/admin/state`, and `POST /v1/admin/clock/advance?seconds=N`. All require the controller token. `GET /healthz` exists on both listeners.

Cross-cutting: **idempotency** (`Idempotency-Key` header on POSTs) and **signed webhooks** (see below).

---

## The PaymentIntent state machine

This is the heart of the simulator. A PaymentIntent moves through Stripe's exact lifecycle, driven by the test card used (see [`PaymentIntentMachine.kt`](src/main/kotlin/com/fakestripe/statemachine/PaymentIntentMachine.kt)):

```
create (no card) ─────────────► requires_payment_method
create (with card) ───────────► requires_confirmation
                                        │ confirm
                    ┌───────────────────┼───────────────────┐
              good card            3DS card             declined card
                    │                   │                     │
      capture=auto  │  capture=manual   ▼                     ▼
        ▼           ▼            requires_action     requires_payment_method
    succeeded  requires_capture                       (+ last_payment_error,
                    │ capture                           402 card_error raised)
                    ▼
                succeeded

any non-terminal state ── cancel ──► canceled
```

- **Automatic capture** (default): a good card goes straight to `succeeded`, a Charge is created (`captured: true`), and `amount_received` is set.
- **Manual capture** (`capture_method=manual`): a good card goes to `requires_capture` with `amount_capturable` set; `POST .../capture` then moves it to `succeeded`.
- **Decline**: a Charge with `status: failed` is recorded, the PaymentIntent returns to `requires_payment_method` with `last_payment_error`, and the request returns **HTTP 402** with a `card_error` whose `payment_intent` is embedded — exactly as real Stripe does, so SDKs raise `CardError`.

### Test cards

The card **number** decides the outcome (Stripe's documented test cards). You can pass a raw number when creating a PaymentMethod, or use Stripe's shared test tokens directly (`payment_method=pm_card_visa`):

| Token / Number | Outcome |
|---|---|
| `pm_card_visa` / `4242 4242 4242 4242` | Succeeds |
| `pm_card_mastercard`, `pm_card_amex`, `pm_card_discover` | Succeed |
| `pm_card_chargeDeclined` / `4000 0000 0000 0002` | Declined — `generic_decline` |
| `pm_card_chargeDeclinedInsufficientFunds` / `…9995` | Declined — `insufficient_funds` |
| `pm_card_chargeDeclinedLostCard` / `…9987` | Declined — `lost_card` |
| `pm_card_chargeDeclinedExpiredCard` / `…0069` | Declined — `expired_card` |
| `pm_card_chargeDeclinedIncorrectCvc` / `…0127` | Declined — `incorrect_cvc` |
| `pm_card_authenticationRequired` / `4000 0025 0000 3155` | `requires_action` (3-D Secure) |

---

## Determinism, seeding & reset

Training requires reproducible starting states. `FAKE_STRIPE_SEED=N` (or `POST /v1/admin/reset?seed=N`) builds a world of customers, saved cards and past payments where **the same seed always yields the same objects, down to their IDs**. IDs are drawn from a seeded PRNG, and the generator's position is snapshotted so it resumes deterministically after a restart.

```bash
curl -X POST "http://localhost:12112/v1/admin/reset?seed=42&episode_id=run-42-001" \
  -H "X-Siere-Control-Token: gym_control_local"
# -> { "object": "admin.reset", "seed": 42, "episode_id": "run-42-001",
#      "scenario": null, "state_revision": 1,
#      "clock": { "mode": "free", "current_time": ... }, "task_context": {}, ... }
```

Pass a scenario ID to build a task-ready Gym world. The reset response returns
only learner-safe instruction context; the privileged state export contains the
controller-only IDs needed to verify the result.

| Scenario | Seeded world |
|---|---|
| `duplicate_payments` | Two successful, unrefunded charges for one customer plus realistic distractors. |
| `duplicate_payments_refund_response_loss` | The same duplicate world plus one controller-seeded `refund.create` response loss after commit. An idempotent retry replays the committed success without a second refund. |
| `past_due_subscription` | A failed renewal, declining default card, and usable replacement card. |
| `upgrade_candidate` | An active Basic-monthly subscription with a Pro-annual target and another active subscription. |
| `cancel_candidate` | An active subscription with paid-through context and an unrelated subscription. |
| `payment_investigation` | Several plausible payments and one failed or authentication-required intent. |

For every scenario, customer names, IDs, amounts, and target ordering vary by
seed while the same `{seed, scenario}` pair reproduces exactly.

```bash
curl -X POST \
  "http://localhost:12112/v1/admin/reset?seed=42&scenario=duplicate_payments&episode_id=run-42-001" \
  -H "X-Siere-Control-Token: gym_control_local"
# -> { "object": "admin.reset", "seed": 42, "episode_id": "run-42-001",
#      "scenario": "duplicate_payments",
#      "state_revision": 1, "clock": { "mode": "manual", "current_time": ... },
#      "task_context": { "customer_name": "..." }, ... }
```

An unsupported scenario returns Stripe-shaped HTTP `400` without replacing the
current world. Reset persists the complete candidate snapshot before returning
success, so a failed write is never acknowledged and the live world stays intact.

### Episode correlation

Controllers may supply `episode_id` on reset. IDs are 1–128 characters using
letters, numbers, `.`, `_`, `:`, or `-`; omitting the parameter clears correlation
for compatibility. The value persists with the snapshot, is stamped onto every
internal event trace, and appears in request logs as `episode_id=<value>`.

Provider Event JSON remains Stripe-compatible and does not expose the episode ID.
Controllers see it in `GET /v1/admin/state`, while event queries can select the
current episode without changing their response shape:

```bash
BASE=http://localhost:12111
KEY="Authorization: Bearer sk_test_123"
curl -s "$BASE/v1/events?episode_id=run-42-001" -H "$KEY"
docker compose logs fake-stripe | grep 'episode_id=run-42-001'
```

### Episode clock

Legacy resets without a scenario default to `clock_mode=free`, which follows host
wall time. Scenario resets default to `clock_mode=manual`: their starting time is
derived from the seed and scenario, so the same pair reproduces the same object
timestamps across runs. Either mode can be selected explicitly on reset.

The controller can advance a manual clock without exposing that capability to an
agent. The new time and any resulting Checkout Session expirations are persisted
before success is returned:

```bash
curl -X POST "http://localhost:12112/v1/admin/clock/advance?seconds=3600" \
  -H "X-Siere-Control-Token: gym_control_local"
# -> { "object": "admin.clock", "mode": "manual", "current_time": ...,
#      "advanced_by": 3600, "state_revision": 2 }
```

Advancing a free clock returns HTTP `400`. Manual time and mode survive process
restarts, and every runtime-created object and event obtains its timestamp from
this clock.

## Persistence

State lives in memory and is snapshotted to `FAKE_STRIPE_DATA` after every mutation (including recorded declines). On startup the snapshot is loaded if present, so **a customer created before a restart is still there afterward**. Delete the snapshot (or call `reset`) to start clean.

Each persisted mutation advances a monotonic `state_revision`. The revision is
stored in the snapshot, survives process restarts, advances across resets and
manual clock changes, and does not change when state is merely exported.

## Privileged state export

Gym verifiers need one atomic before/after view rather than dozens of paginated
provider calls. Configure a controller token and call the simulator-only export:

```bash
FAKE_STRIPE_CONTROL_TOKEN=gym_control_local ./gradlew run

curl -s http://localhost:12112/v1/admin/state \
  -H "X-Siere-Control-Token: gym_control_local"
```

The response contains `episode_id`, `state_revision`, persisted clock state, scenario verifier metadata, the
deterministic ID sequence, and every
customer, payment method, payment intent, charge, refund, product, price,
subscription, invoice, checkout session, billing-portal session, event, and
idempotency record. Raw card numbers, client secrets, raw idempotency keys, and
secret/password/token fields are removed. Idempotency keys are represented only
by SHA-256 so duplicate-submission checks remain possible without revealing the
credential-like input.

This endpoint is a **Gym control-plane API, not a Stripe API**. Never expose the
controller token to the client, the agent, or any provider request path.
Production startup registers no admin routes on the actor listener.
The controller listener defaults to host loopback, Docker publishes it only on
host loopback, and every controller route also requires the controller token.
`ControlPlaneIsolationTest` locks down both route surfaces and listener defaults.

## Error shapes

Errors use Stripe's exact envelope, so client libraries deserialize them into the right exception types:

```json
{ "error": {
    "type": "card_error",
    "code": "card_declined",
    "decline_code": "insufficient_funds",
    "message": "Your card has insufficient funds.",
    "doc_url": "https://stripe.com/docs/error-codes/card_declined",
    "payment_intent": { "id": "pi_…", "status": "requires_payment_method", … }
} }
```

HTTP status codes follow Stripe: `400` invalid request / missing param, `402` card error, `404` resource missing, `500` api error.

---

## Refunds & idempotency

**Refunds** (`POST /v1/refunds` with a `charge` or `payment_intent`) are full or partial; a charge can be refunded repeatedly until the total is reached, and the charge's `amount_refunded` / `refunded` and embedded `refunds` list update accordingly. Over-refunding is rejected. This is the classic "refund the smaller of two charges" task material.

**Idempotency**: any POST carrying an `Idempotency-Key` header caches its first response. A repeat with the same key replays that exact response (with an `Idempotent-Replayed: true` header) instead of, say, charging twice; a repeat with the *same key but a different body* returns a `400 idempotency_error`. Keys survive restarts (they're snapshotted).

The `duplicate_payments_refund_response_loss` Gym scenario consumes one deterministic fault after
the refund mutation commits. The first response is `503` with `Stripe-Should-Retry: true`, while
the successful response has already been bound to the request's idempotency key. Repeating the
exact request returns that cached `200` and does not invoke the refund route again. The privileged
state export records the fault ID, phase, remaining count, and injected count; actor routes cannot
configure the plan.

## Recurring billing (subscriptions & invoices)

`POST /v1/subscriptions` (with `customer` and `items[0][price]`) bills a first invoice for the period and, if the customer has a payment method, charges it via a real PaymentIntent — so the invoice comes back `paid` with a `payment_intent`. The seeded world ships a small Basic/Pro catalog so this works out of the box.

**Upgrades with proration** are the rich, multi-step case. `POST /v1/subscriptions/{id}` with a new `items[0][price]` (e.g. Basic monthly → Pro annual) generates a `subscription_update` invoice with two proration lines — a credit for unused time on the old price and a charge for the remainder on the new price — and switches the billing period. A checker can assert the new invoice total. `DELETE` cancels immediately; `cancel_at_period_end=true` schedules it.

**Renewals and dunning.** There is no background billing clock, so renewals happen on demand: `POST /v1/admin/subscriptions/{id}/renew` rolls the subscription into its next period, issues a `subscription_cycle` invoice and tries to collect. With a good card that is a second `invoice.paid`; with a declining one the invoice stays `open`, `invoice.payment_failed` fires and the subscription goes **`past_due`** — not cancelled, because real dunning retries for days. A subscription already set to `cancel_at_period_end` simply ends instead of renewing. A first invoice that never collects leaves the subscription `incomplete`, as it does at Stripe.

**Deleting a customer** (`DELETE /v1/customers/{id}`) immediately cancels their non-terminal subscriptions, emitting `customer.subscription.deleted` for each — matching Stripe, and the reason an erasure/GDPR path can be tested here at all.

## Hosted checkout & customer portal

Two Stripe endpoints answer with a **URL a browser is meant to open**, not just JSON — so the simulator serves those pages itself.

```bash
# Create a session; `url` points back at this simulator
curl -s -X POST "http://localhost:12111/v1/checkout/sessions" -u sk_test_123: \
  -d mode=subscription -d "line_items[0][price]=price_..." -d "line_items[0][quantity]=1" \
  -d customer_email=reader@example.com -d client_reference_id=<your user id> \
  --data-urlencode "success_url=https://example.com/account?checkout=done" \
  --data-urlencode "cancel_url=https://example.com/upgrade"
# -> { "id": "cs_...", "url": "http://localhost:12111/checkout/cs_...", "status": "open", ... }
```

Open that URL and you get a plain HTML page (no JavaScript) showing the amount, interval and email, a card field that accepts any [test card](#test-cards), and two buttons:

- **Pay** — creates the customer + subscription, emits **`checkout.session.completed`**, *then* `303`s to `success_url` (with `{CHECKOUT_SESSION_ID}` substituted). A declining card records the failed attempt and grants nothing: no customer, no subscription, session still `open`. Pressing Pay twice never bills twice.
- **Cancel** — redirects to `cancel_url`, creating nothing.

> The **event**, not the redirect, is what should grant access. An integration that upgrades a user on the return trip from `success_url` looks identical in a browser and is broken in production — so the simulator keeps both paths honest: the redirect carries no authority, and the event is emitted only once the customer and subscription really exist. `client_reference_id` is echoed back on the event so you can find whoever started the checkout.

`POST /v1/billing_portal/sessions` (with `customer` and `return_url`) works the same way, and its page does the two things integrations have code behind: **cancel** (sets `cancel_at_period_end`, keeping the period already paid for) and **renew** (undoes it) — each emitting `customer.subscription.updated`.

## Webhooks & events

Mutations record **Events** (`customer.created`, `payment_intent.succeeded`, `charge.refunded`, `invoice.paid`, `invoice.payment_failed`, `checkout.session.completed`, `customer.subscription.created|updated|deleted`, …), retrievable at `/v1/events` and filterable by `episode_id`. Correlation stays internal and is deliberately omitted from provider Event JSON. If a webhook URL is configured (env or `POST /v1/admin/webhook`), each event is POSTed to it with a real `Stripe-Signature: t=<ts>,v1=<hmac>` header — HMAC-SHA256 over `"<ts>.<body>"` with the endpoint secret, exactly what `stripe.Webhook.constructEvent` verifies. Delivery is asynchronous and never blocks the API response.

```bash
# Point deliveries at your receiver, then watch signed events arrive
curl -s -X POST "http://localhost:12112/v1/admin/webhook" \
  -H "X-Siere-Control-Token: gym_control_local" \
  -d url=http://localhost:9000/hook -d secret=whsec_abc
# -> { "object": "admin.webhook", "url": "...", "secret_configured": true }
```

The configured secret is never echoed back — only whether one is set. Delivery
targets must be `http`/`https`; redirects are not followed, and link-local,
multicast, and wildcard addresses are refused so a receiver cannot bounce
deliveries at a metadata endpoint.

---

## Project layout

```
src/main/kotlin/com/fakestripe/
├── Application.kt              # Ktor wiring: auth, idempotency, routing, error rendering
├── billing/
│   ├── BillingOps.kt          # Subscription create/upgrade(proration)/renew/cancel + invoicing
│   └── CheckoutOps.kt         # Checkout Session lifecycle (create -> pay -> completed event)
├── cards/TestCards.kt         # Stripe test cards -> outcomes (data-driven)
├── error/StripeError.kt       # StripeException + exact wire error shape
├── hosted/Html.kt             # Markup for the browser-facing checkout & portal pages
├── model/                     # Customer, PaymentMethod, PaymentIntent, Charge, Refund,
│                              #   Product, Price, Subscription, Invoice, CheckoutSession,
│                              #   BillingPortalSession, Event, list/expand
├── seed/Seeder.kt             # Deterministic seeded world (customers, cards, catalog)
├── statemachine/
│   └── PaymentIntentMachine.kt# The full PaymentIntent lifecycle
├── store/
│   ├── DataStore.kt           # In-memory world + event recording
│   ├── Simulator.kt           # Lock + read/write/reset + webhook draining
│   ├── SimulatorClock.kt      # Free/manual episode time + deterministic reset time
│   ├── Snapshot.kt            # JSON snapshot-to-disk load/save
│   └── Idempotency.kt         # Cached idempotent responses
├── webhook/WebhookDispatcher.kt # Signs + POSTs events to a configurable URL
├── routes/                    # One file per resource + Http helpers
└── util/                      # Deterministic IDs + Stripe form-param parsing
src/test/kotlin/com/fakestripe/  # SimulatorTest, Refund, Idempotency, ProductPrice,
│                                #   Subscription, Checkout, BillingPortal, Webhook,
│                                #   StripeJavaSdkTest
sdk-tests/                     # official stripe-python SDK e2e suite (pytest)
postman/                       # Postman collection (+ generator that keeps it in sync)
```

## SDK compatibility (the "killer test")

The strongest proof of realism is Stripe's **own client libraries, unmodified**, driving the simulator — the only change is the base URL (a first-class SDK config option, not a patch). If our response shapes were wrong, the SDKs would fail to deserialize into their typed objects.

- **stripe-java** — `StripeJavaSdkTest` runs inside the normal Gradle suite (`./gradlew test`). It boots the server on an ephemeral port, calls `Stripe.overrideApiBase(...)`, and runs customer → confirm → retrieve → list → declined-card (`CardException`).
- **stripe-python** — [`sdk-tests/`](sdk-tests/README.md) is a pytest suite pointing `stripe.api_base` at a running instance. It covers customers, confirm/decline, manual capture, **refunds**, **products/prices/subscriptions/invoices**, **idempotency keys**, and a full **hosted checkout** run (SDK creates the session, a browser-shaped POST pays on the page, the SDK reads back the completed session and its subscription). `pip install -r sdk-tests/requirements.txt && pytest sdk-tests`.

## Postman collection

[`postman/fake-stripe.postman_collection.json`](postman/fake-stripe.postman_collection.json) covers all endpoints, with collection-level `Bearer {{apiKey}}` auth, separate `{{baseUrl}}` and `{{controlUrl}}` variables, and scripts that capture created IDs so requests chain. Controller requests carry `{{controlToken}}`. It's produced by [`postman/generate_collection.py`](postman/generate_collection.py) — **when an endpoint changes, edit the generator and re-run it** so the collection never drifts.

## Tested

The suite runs through the real routing, state machine, and billing logic (`./gradlew test`): confirm/decline/manual-capture, refunds (partial→full→over-refund), idempotency (replay + conflict), products/prices, a full subscribe → upgrade-with-proration → cancel flow, **hosted checkout** (pay, decline, double-pay, cancel, expiry, `checkout.session.completed` contents), **customer portal** cancel/resume, **customer deletion cancelling subscriptions**, **renewal and dunning** (`past_due` + `invoice.payment_failed`), signed webhook delivery (a real local receiver verifies the HMAC), Stripe-shaped `404`, missing-key `401`, seed determinism, **privileged full-state export authorization/redaction/revision persistence**, **20-seed generation, solvability, variation, and exact reproduction for all five Gym scenarios**, **free/manual clock determinism, authorization, persistence, rollback, and time-driven expiry**, **actor/controller route and listener isolation**, **episode-correlated logs/events with unchanged provider shapes**, failed-reset persistence safety, and the full unmodified **stripe-java** flow (which also deserializes both hosted-session objects). A separate **stripe-python** suite (`pytest sdk-tests`) exercises the same flows through the official Python SDK. Persistence-across-restart and cross-seed determinism are verified against the running server.

---

## Roadmap

Done: payments-core object model + PaymentIntent state machine, **refunds**, **idempotency keys**, **products/prices/subscriptions/invoices** (with proration, on-demand renewal and dunning), **hosted Checkout + customer portal pages**, **signed webhooks + events**, deterministic seeding, snapshot persistence, Stripe-shaped errors, API-key auth, and **both official SDKs (java + python) running unmodified** against the server.

Next, in build order:

1. **Task pack** — a set of graded tasks (plain-English instruction + seeded world + automatic checker) covering easy → hard, so the gym ships with a benchmark, not just an API.
2. **MCP layer** — expose the API as tools an agent can call directly (or point the open-source Stripe MCP server at it).
3. **Rest of the validation suite** — contract tests against Stripe's published OpenAPI, response-shape diffs vs `stripe-mock`, and record/replay golden files from a Stripe test-mode account.
4. **A worked training/eval example** — run a small model against the task pack and publish the score curve.

## Security model

This is a development tool, and its security posture is shallow on purpose. Know
what it does and does not give you:

**What is enforced**

- The controller plane (reset, clock, state export, webhook config) is a **separate
  listener on a separate port behind its own token**, disabled entirely unless
  `FAKE_STRIPE_CONTROL_TOKEN` is set. Admin routes are never registered on the actor
  listener, and `ControlPlaneIsolationTest` locks that down.
- Both listeners **default to loopback**. Exposing either is an explicit act.
- The state export **redacts** card numbers, client secrets, and every
  `*_secret` / `*_token` / `password` / `api_key` field, and represents idempotency
  keys only as SHA-256.
- Redirect targets are restricted to `http`/`https`; hosted pages send a strict CSP.
- **Live-mode API keys are refused.**

**What is not**

- **Authentication is simulated.** Any test-mode key is accepted; there is no notion
  of who you are, and no authorization between objects.
- **No rate limiting, no request-body cap**, and event/idempotency history grows for
  the life of a snapshot.
- **Snapshots are plaintext**, including card numbers typed into the hosted page.
- Object IDs are **deterministic by design** — predictable to anyone with the seed.

Do not expose this to an untrusted network, and never give it real cardholder data,
credentials, or production data. Full detail in [DISCLAIMER.md](DISCLAIMER.md) and
[SECURITY.md](SECURITY.md).

## Honest limitations (today)

- Payments core + recurring billing only: no Connect, Issuing, Terminal, Radar, Tax, Treasury, real card networks.
- `requires_action` (3-D Secure) is represented but there is no completion endpoint to walk it to `succeeded`; on the hosted checkout page a 3-D Secure card is refused with an explanation rather than pretending to authenticate.
- Proration uses fixed interval lengths (30-day month, 365-day year) rather than calendar-exact dates — deterministic, so totals are predictable for checkers, but not identical to real Stripe's day counting.
- Subscriptions don't auto-advance to the next period (no background billing clock); renewals are triggered explicitly via `POST /v1/admin/subscriptions/{id}/renew`.
- The hosted pages redirect like Stripe but look nothing like it, and Checkout supports only `payment` and `subscription` modes with existing prices — no `setup` mode, `price_data`, promotion codes, tax, or address collection.
- Card numbers are accepted directly for convenience (real Stripe restricts raw PANs); intentional for a local test simulator.

---

## Built by Siere Soft

A European software studio building AI-native products and **verifiable agent environments** — small, spec-first. This is one of our open tools.

Studio: **[sieresoft.com](https://sieresoft.com)** · **hello@sieresoft.com**

Building agents, evals, or payments and want help? **[Book a call →](https://sieresoft.com/contact)**

Licensed under Apache-2.0. Contributions welcome — see [CONTRIBUTING](CONTRIBUTING.md).
