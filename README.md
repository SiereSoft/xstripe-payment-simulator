# fake-stripe

A **stateful simulator of Stripe's payments core**, written in Kotlin (Ktor) and shipped as a Docker container. It imitates Stripe's HTTP API closely enough that an AI agent — or Stripe's own client libraries — can drive it, while remembering state across requests and restarts and re-creating an identical world from a seed.

This is the **foundation slice** of the [Prime Intellect "fake Stripe" bounty](#roadmap): the object model, the PaymentIntent status state machine (the hard, valuable part), the core endpoints, deterministic seeding, and Stripe-exact error shapes. See [Roadmap](#roadmap) for what is intentionally not here yet.

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

## Quick start

### With Docker (one command)

```bash
docker compose up --build
# API now on http://localhost:12111
```

### Without Docker (JDK 11+)

```bash
./gradlew run          # or: gradle run
# API on http://localhost:12111
```

### Smoke test

```bash
BASE=http://localhost:12111
KEY="Authorization: Bearer sk_test_123"   # any sk_... value works

# What's in the seeded world? (admin endpoints need no key)
curl -s $BASE/v1/admin/health

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
| `HOST` | `0.0.0.0` | Bind address |
| `FAKE_STRIPE_SEED` | `1` | Seed for the initial world (only used when no snapshot exists) |
| `FAKE_STRIPE_DATA` | `data/state.json` | Where the state snapshot is written |

### Authentication

Every `/v1` business endpoint requires an API key, exactly like real Stripe — send `Authorization: Bearer sk_test_...`. Any `sk_...` value is accepted (this is a fake; we don't validate which key); a **missing** key returns `401 authentication_error`. The admin/health endpoints (`/`, `/healthz`, `/v1/admin/*`) are keyless so reset and training stay frictionless. The official SDKs send a key automatically.

---

## What it imitates (endpoints)

Requests are **`application/x-www-form-urlencoded`** with bracket notation (`metadata[key]=v`, `expand[]=customer`) — exactly like real Stripe. Responses are JSON in Stripe's object shapes. Every list supports `limit`, `starting_after`, `ending_before`; most objects support `expand[]`.

**Customers** — `POST/GET/POST(update)/DELETE /v1/customers[/{id}]`, `GET /v1/customers`, `GET /v1/customers/{id}/payment_methods`

**PaymentMethods** — `POST/GET/POST(update) /v1/payment_methods[/{id}]`, `POST .../attach`, `POST .../detach`, `GET /v1/payment_methods?customer=…`

**PaymentIntents** — `POST/GET/POST(update) /v1/payment_intents[/{id}]`, `POST .../confirm`, `POST .../capture`, `POST .../cancel`, `GET /v1/payment_intents`

**Charges** — `GET/POST(update) /v1/charges/{id}`, `GET /v1/charges`

**Admin (non-Stripe)** — `GET /healthz`, `GET /v1/admin/health`, `POST /v1/admin/reset?seed=N`

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
curl -X POST "http://localhost:12111/v1/admin/reset?seed=42"
# -> { "object": "admin.reset", "seed": 42, "customers": 6, "payment_methods": 4, ... }
```

## Persistence

State lives in memory and is snapshotted to `FAKE_STRIPE_DATA` after every mutation (including recorded declines). On startup the snapshot is loaded if present, so **a customer created before a restart is still there afterward**. Delete the snapshot (or call `reset`) to start clean.

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

## Project layout

```
src/main/kotlin/com/fakestripe/
├── Application.kt              # Ktor wiring, plugins, routing, error rendering
├── cards/TestCards.kt         # Stripe test cards -> outcomes (data-driven)
├── error/StripeError.kt       # StripeException + exact wire error shape
├── model/                     # Customer, PaymentMethod, PaymentIntent, Charge, list/expand
├── seed/Seeder.kt             # Deterministic seeded world builder
├── statemachine/
│   └── PaymentIntentMachine.kt# The full PaymentIntent lifecycle
├── store/
│   ├── DataStore.kt           # In-memory world
│   ├── Simulator.kt           # Lock + read/write/reset around the store
│   └── Snapshot.kt            # JSON snapshot-to-disk load/save
└── util/                      # Deterministic IDs + Stripe form-param parsing
src/test/kotlin/com/fakestripe/
├── SimulatorTest.kt           # end-to-end tests via Ktor test host
└── StripeJavaSdkTest.kt       # official stripe-java SDK, unmodified, vs the simulator
sdk-tests/                     # official stripe-python SDK e2e suite (pytest)
postman/                       # Postman collection (+ generator that keeps it in sync)
```

## SDK compatibility (the "killer test")

The strongest proof of realism is Stripe's **own client libraries, unmodified**, driving the simulator — the only change is the base URL (a first-class SDK config option, not a patch). If our response shapes were wrong, the SDKs would fail to deserialize into their typed objects.

- **stripe-java** — `StripeJavaSdkTest` runs inside the normal Gradle suite (`./gradlew test`). It boots the server on an ephemeral port, calls `Stripe.overrideApiBase(...)`, and runs customer → confirm → retrieve → list → declined-card (`CardException`).
- **stripe-python** — [`sdk-tests/`](sdk-tests/README.md) is a pytest suite pointing `stripe.api_base` at a running instance. `pip install -r sdk-tests/requirements.txt && pytest sdk-tests`.

## Postman collection

[`postman/fake-stripe.postman_collection.json`](postman/fake-stripe.postman_collection.json) covers all endpoints, with collection-level `Bearer {{apiKey}}` auth, `{{baseUrl}}`/`{{apiKey}}` variables, and scripts that capture created IDs so requests chain. It's produced by [`postman/generate_collection.py`](postman/generate_collection.py) — **when an endpoint changes, edit the generator and re-run it** so the collection never drifts.

## Tested

Nine tests run through the real routing and state machine (`./gradlew test`): customer creation, successful confirm, declined-card `402`, manual capture, Stripe-shaped `404`, missing-key `401`, keyless admin, seed determinism, and the full unmodified **stripe-java** flow. The **stripe-python** suite adds six more. Persistence-across-restart and cross-seed determinism are verified against the running server.

---

## Roadmap

Done so far: payments-core object model + PaymentIntent state machine, deterministic seeding, snapshot persistence, Stripe-shaped errors, API-key auth, and **both official SDKs (java + python) running unmodified** against the server. Remaining bounty deliverables, in build order:

1. **Refunds** (full + partial) and the refund object.
2. **Idempotency keys** (same `Idempotency-Key` ⇒ same response, no double charge).
3. **Products, Prices, Subscriptions, Invoices** (recurring / multi-step task material).
4. **Webhooks & Events** — the simulator POSTs signed event notifications to a configurable URL.
5. **Rest of the validation suite** — the unmodified-SDK flows are done (above); still to add: contract tests against Stripe's published OpenAPI, response-shape diffs vs `stripe-mock`, and record/replay golden files from Stripe test mode.
6. **MCP layer** — point the existing open-source Stripe MCP server at this simulator, or ship a thin wrapper.
7. **30+ tasks** in Prime Intellect's `verifiers` format with automatic checkers + difficulty calibration.
8. **prime-rl training run** with a score curve.

## Honest limitations (today)

- Payments core only: no Connect, Issuing, Terminal, Radar, Tax, Treasury, real card networks.
- No refunds, subscriptions, invoices, webhooks or idempotency **yet** (see roadmap).
- `requires_action` (3-D Secure) is represented but there is no completion endpoint to walk it to `succeeded`.
- Card numbers are accepted directly for convenience (real Stripe restricts raw PANs); this is intentional for a local test simulator.
```
