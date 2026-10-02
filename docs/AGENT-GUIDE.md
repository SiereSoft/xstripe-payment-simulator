# Agent guide: driving the Stripe simulator

The simulator has two listeners. The **actor** listener (default `127.0.0.1:12111`) is Stripe: give
its URL to the system under test as the Stripe API base (`https://api.stripe.com` → `http://127.0.0.1:12111`)
and any `sk_test_…` key. The **controller** listener (default `127.0.0.1:12112`) speaks the Siere
simulator control protocol, version 1 ([CONTROL-PROTOCOL.md](CONTROL-PROTOCOL.md)): reset, clock,
faults, journal, webhooks and snapshots. Never give the controller URL or token to the system under test.

## Starting it

```bash
./gradlew installDist
PORT=12111 CONTROL_PORT=12112 \
FAKE_STRIPE_CONTROL_TOKEN=$(openssl rand -hex 24) \
FAKE_STRIPE_DATA=/tmp/xstripe/state.json \
FAKE_STRIPE_CLOCK_MODE=manual \
  build/install/fake-stripe/bin/fake-stripe
```

| Variable | Default | Meaning |
|---|---|---|
| `PORT`, `HOST` | `12111`, `127.0.0.1` | Actor listener |
| `CONTROL_PORT`, `CONTROL_HOST` | `12112`, `127.0.0.1` | Controller listener |
| `FAKE_STRIPE_CONTROL_TOKEN` | — | Controller token, at least 16 characters. Without one the controller port is **not opened** (the log says so) |
| `FAKE_STRIPE_DATA` | `data/state.json` | World file; the journal is `state.journal.jsonl` beside it, snapshots in `snapshots/` beside it |
| `FAKE_STRIPE_SEED` | `1` | Configured seed (any text; a reset without `seed` uses it) |
| `FAKE_STRIPE_CLOCK_MODE` | `free` | Clock of the first world built when no state file exists |
| `FAKE_STRIPE_WEBHOOK_URL`, `FAKE_STRIPE_WEBHOOK_SECRET` | —, `whsec_test` | Webhook endpoint and signing secret (the URL overrides the one a state file remembers) |
| `FAKE_STRIPE_PUBLIC_URL` | request host | Base URL written into hosted Checkout and portal links |

`GET /healthz` answers `{"status":"ok"}` on both listeners without authentication.

## Conventions

Every controller route under `/v1/admin/` needs `X-Siere-Control-Token`; a missing or wrong token
answers `403 {"error":{"code":"forbidden",…}}`. Every controller response carries
`X-Siere-Control-Protocol: 1`. Bodies are JSON; times are ISO-8601 UTC with milliseconds. Errors are
`{"error":{"code","message","type":"admin_error","param"?}}` with codes `invalid_request` (400),
`forbidden` (403), `not_found` (404), `conflict` (409) and the specific codes below.

## Lifecycle

| Route | Body | Answer |
|---|---|---|
| `GET /v1/admin/health` | | `status`, `simulator: "stripe"`, `version`, `protocol: 1`, `seed`, `episode_id`, `clock{now, mode, current_time}`, `revision`, `journal_seq`, plus world counts |
| `POST /v1/admin/reset` | `seed` (text), `episode_id`, `scenario`, `clock_mode` (`manual` default), `now` (manual start; default: a deterministic time in 2027 derived from seed and scenario) | Like health, plus `scenario`, `state_revision`, `task_context` |
| `GET /v1/admin/state` | | The whole world, secrets removed; includes `revision`, `faults`, `webhooks`, `config` |
| `GET`, `PATCH /v1/admin/config` | `webhook_delivery`: `auto` \| `manual` | `{webhook_delivery, webhook_url, webhook_secret_configured, clock_mode}` |

Reset clears faults, the outbox and the journal (numbering continues; the reset itself is the first
new entry) and keeps snapshots and the configuration (webhook endpoint, delivery mode). The same
seed, scenario and sequence of requests and clock moves produce the same ids, bodies, request ids
and callbacks. 0.1.x callers may still pass `?seed=N&scenario=…&clock_mode=…&episode_id=…` in the
query string; those keep 0.1.x's clock default (`free` without a scenario).

Scenarios: `duplicate_payments` (`_easy`, `_medium`, `_hard`, `_safety`, `_refund_response_loss`,
`_collateral_subscription`), `past_due_subscription`, `upgrade_candidate`, `cancel_candidate`,
`payment_investigation`. Their verifier context is in `GET /v1/admin/state` under `scenario`.

## Clock

| Route | Body | Effect |
|---|---|---|
| `GET /v1/admin/clock` | | `{now, mode, current_time}` |
| `POST /v1/admin/clock` | `{"now": "2026-10-05T08:00:00.000Z"}` | Move to an absolute time |
| `POST /v1/admin/clock/advance` | `{"seconds": 3600}` (fractions allowed) or `?seconds=3600` | Move forward, `0 < seconds <= 31536000` |

Both answer `{now, mode: "manual", settled, current_time, state_revision, …}`. A free clock answers
`409 clock_not_manual`; a time before `now` answers `409 clock_backwards`. Moving the clock settles,
in time order, everything that falls due on the way:

- Checkout Sessions expire 24 h after creation (`checkout.session.expired`).
- `customer.subscription.trial_will_end` three days before a trial ends (at once for shorter trials).
- A trial ends: the first period starts at `trial_end`, its invoice is created and charged to the
  default payment method (`invoice.paid` → `active`, or `invoice.payment_failed` → `past_due`).
- A period ends: the subscription renews the same way (or is canceled, when `cancel_at_period_end`).
- Webhook retries fall due (in `auto` delivery mode) and are attempted at their own simulated time.

Objects created by settled work carry the time the work fell due. One long move ends in exactly
the same world as many short ones. In free mode the same work settles as the wall clock reaches it
(checked on every mutating request and once a second).

## Faults

`POST /v1/admin/faults` installs a named fault (`201`, `409 fault_exists` for a taken name);
`GET /v1/admin/faults` lists them; `DELETE /v1/admin/faults/{name}` and `DELETE /v1/admin/faults`
remove them. A fault matches `{"operation": "<id>"}` or `{"method": "POST", "path": "/v1/refunds/**"}`
(`*` one segment, a trailing `**` any remainder):

```json
{"name": "refund-lost", "match": {"operation": "refund.create"}, "kind": "commit_then_error",
 "status": 503, "headers": {"Stripe-Should-Retry": "true"}, "skip": 0, "count": 1}
```

| Kind | Effect on the actor request |
|---|---|
| `error` | Answers `status`/`headers`/`body` (default body: a Stripe `api_error`) without executing |
| `commit_then_error` | Executes and commits, then answers the error; the same `Idempotency-Key` replays the committed result |
| `commit_then_drop` | Executes and commits, then closes the TCP connection without a response byte |
| `drop_before` | Closes the connection without executing |
| `latency` | Waits `delay_ms` (real time, `0..60000`), then answers normally |

`status` is `400..599` (default 500); `count` `1..1000` (default 1); `skip` matching requests pass first (every matching fault
counts a request toward its own `skip`; of those past it, the first installed applies). Faults apply
before the API-key check. Spent faults stay listed with `remaining: 0`. Faults are persisted, carried
by snapshots and cleared by reset. The scenario `duplicate_payments_refund_response_loss` installs
`refund-response-loss` (`commit_then_error`, `refund.create`, 503, `injected_response_loss`).

### Operation identifiers

| Area | Operations |
|---|---|
| Service | `service.info` (`GET /`) |
| Customers | `customer.create`, `customer.list`, `customer.retrieve`, `customer.update`, `customer.delete`, `customer.list_payment_methods` |
| PaymentMethods | `payment_method.create`, `.list`, `.retrieve`, `.update`, `.attach`, `.detach` |
| PaymentIntents | `payment_intent.create`, `.list`, `.retrieve`, `.update`, `.confirm`, `.capture`, `.cancel` |
| Charges | `charge.list`, `charge.retrieve`, `charge.update` |
| Refunds | `refund.create` (`POST /v1/refunds` and `POST /v1/charges/{id}/refunds`), `refund.list`, `refund.retrieve`, `refund.update` |
| Products | `product.create`, `.list`, `.retrieve`, `.update`, `.delete` |
| Prices | `price.create`, `.list`, `.retrieve`, `.update` |
| Subscriptions | `subscription.create`, `.list`, `.retrieve`, `.update`, `subscription.cancel` (`DELETE`) |
| Subscription items | `subscription_item.list`, `.retrieve`, `.update` |
| Invoices | `invoice.list`, `.retrieve`, `.update`, `.pay`, `.void` |
| Checkout | `checkout.session.create`, `.list`, `.retrieve`, `.expire` |
| Billing portal | `billing_portal.session.create` |
| Events | `event.list`, `event.retrieve` |
| Hosted pages | `checkout.page.view`, `checkout.page.pay`, `checkout.page.cancel`, `checkout.page.authenticate_view`, `checkout.page.authenticate`, `billing_portal.page.view`, `billing_portal.page.action` |

## Journal

`GET /v1/admin/journal?after=<seq>&limit=<1..1000>&kind=<kind>&bodies=true` answers
`{"entries": [...], "next", "oldest"}`. `after` is exclusive and `next` is the value for the next
page's `after`; page until a page comes back empty. Kinds: `request` (every actor request but
`/healthz`, with `operation`, `status`, `outcome` `answered|error_injected|dropped|rejected`,
`request_id`, `idempotency_key`, `correlation_id` from `X-Siere-Correlation-Id`, `refs` such as
`{"customer": "cus_…"}`, `fault`), `callback` (one per webhook attempt: `path`, `status`, `outcome`
`delivered|failed|exhausted`, `refs.event`, `refs.attempt`), `state` (scheduled work), `fault`,
`clock` and `control`. Bodies (with `bodies=true`) are redacted: card numbers, CVCs, secrets, tokens
and signatures read `"[redacted]"`. Sequence numbers never restart; the journal keeps the last
10 000 entries.

Each actor response carries Stripe's `Request-Id` header with a seeded id (`req_` + 14 characters);
events an API request causes carry it in `request.id` with the request's `Idempotency-Key` in
`request.idempotency_key`. An idempotent replay answers `Idempotent-Replayed: true` and
`Original-Request: <first request id>`.

## Webhooks

`POST /v1/admin/webhook {"url": "http://127.0.0.1:9000/hook", "secret": "whsec_…"}` sets the endpoint
(the secret is never echoed or persisted). Every event emitted while an endpoint is configured gets a
job in the persisted outbox; nothing is dropped.

| Route | Effect |
|---|---|
| `GET /v1/admin/webhooks?status=pending\|failed\|delivered\|exhausted` | `{"webhooks": [job]}` |
| `GET /v1/admin/webhooks/{id}` | One job with its exact `payload` |
| `POST /v1/admin/webhooks/flush` `{"ids", "reverse", "concurrency", "include_future"}` | Deliver due jobs now (listed ids even when not due; unknown id → 404 before sending) → `{"results": [job]}` |
| `POST /v1/admin/webhooks/{id}/replay` | `201` with a new job: same event id, same bytes |

A job: `{id, event_id, event_type, target, status, attempts, next_attempt_at, last_status, …}`.
`auto` delivery (the default) sends due jobs in the background; `manual` only on flush. Each attempt
is signed `Stripe-Signature: t=<attempt time>,v1=<HMAC-SHA256>` with the simulated time of the
attempt. A non-2xx answer (redirects included) or no answer schedules a retry after 1, 2, 4, 8, 16
and 32 hours (attempts at +0, +1, +3, +7, +15, +31, +63 h); after that the job is `exhausted`.

## Snapshots

| Route | Effect |
|---|---|
| `POST /v1/admin/snapshots` `{"name", "replace"}` | Save the world: `201 {name, created_at, clock, revision, episode_id, format, bytes}`; `409 snapshot_exists` unless `replace` |
| `GET /v1/admin/snapshots`, `GET /v1/admin/snapshots/{name}` | Metadata |
| `DELETE /v1/admin/snapshots/{name}` | `204` |
| `POST /v1/admin/snapshots/{name}/restore` | Replace the world; answers like health; `409 snapshot_incompatible` for a file that is not a `fake-stripe.world/1` world |

A snapshot holds entities, clock, id and request-id positions, faults, outbox and configuration —
not the journal (a restore appends a `control` entry). `revision` only ever increases.

## Recipes

**Sell a licence the way the SSF Control Plane does.** `POST /v1/customers`; `POST /v1/prices` with
`product_data[name]` and `recurring[interval]=month`; `POST /v1/checkout/sessions` with
`mode=subscription`, `customer`, `line_items[0][price]` (plan) and `line_items[1][price]`
+ `line_items[1][quantity]` (add-on), `success_url=https://…/done?session={CHECKOUT_SESSION_ID}`,
`cancel_url`, `client_reference_id`, `metadata[storeId]`, `subscription_data[metadata][storeId]` and
optionally `subscription_data[trial_period_days]`. Then play the customer:
`POST /checkout/{id}/pay` with `card_number=4242424242424242` (`303` to the success URL with the
session id substituted). The endpoint receives `customer.subscription.created`, `invoice.paid` and
`checkout.session.completed`.

**Strong customer authentication.** Pay with `4000002500003155`: the page redirects to
`/checkout/{id}/authenticate`; `POST` it with `outcome=complete` (charge succeeds; in subscription
mode the card is saved as authenticated and later renewals succeed off-session) or `outcome=fail`
(nothing is created; the session stays open). `4000000000003220` asks for authentication every
time, so its off-session renewals fail.

**Dunning.** Attach a declining card (`4000000000000002`, or `4000000000009995` for insufficient
funds) as the subscription's default and advance the clock past `current_period_end`:
`invoice.payment_failed`, then `customer.subscription.updated` with `past_due`.

**Lost response.** Install `{"name":"x","match":{"operation":"refund.create"},"kind":"commit_then_drop"}`,
send a refund with an `Idempotency-Key`, watch the connection close, retry with the same key and get
the committed refund back.
