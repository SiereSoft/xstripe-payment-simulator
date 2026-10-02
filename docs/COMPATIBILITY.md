# Compatibility with Stripe

Baseline: Stripe API version **2024-06-20**, the wire shapes the simulator answers whatever
`Stripe-Version` a client sends. The modelled operations are pinned to Stripe's own OpenAPI document
(`github.com/stripe/openapi`, tag `v1267`, the last tag whose `info.version` is 2024-06-20) in
[`contracts/stripe-openapi-subset.json`](../contracts/stripe-openapi-subset.json), with SHA-256
digests of the upstream file, the subset and every operation in
[`contracts/manifest.json`](../contracts/manifest.json).

## Evidence

1. **Unit and HTTP tests** (`./gradlew check`): every behaviour below, over the in-process engine and
   over the real Netty listeners; line coverage is gated at 90 %.
2. **Official SDKs, unmodified**: stripe-java 33.1.0 (`StripeJavaSdkTest`, `StripeJavaSdkBillingTest`:
   prices with `product_data`, trials, several Checkout line items with `subscription_data`,
   subscription items, `expand[]`) and stripe-python 15.3.0 (`sdk-tests/`) deserialize every answer.
3. **Conformance recordings** (`conformance/golden/`, replayed by `conformance/compare.py verify` in
   CI): normalized answers of nine SDK flows. The committed recordings are the simulator's own answers
   (`baseline` mode, `golden/SOURCE.json`) — a regression baseline. Recording Stripe itself needs a
   Stripe test key (`compare.py capture`) and was not done for this release: the simulator's tooling
   never calls Stripe's API.
4. **Upstream drift** (`tools/check_upstream.py`, weekly `.github/workflows/upstream.yml`): verifies
   the pinned document and reports operations of the modelled surface that Stripe has changed since
   the last human review. It talks to GitHub only.

## Fidelity matrix

| Surface | Modelled | Deliberate simplifications |
|---|---|---|
| Authentication | Any `sk_test_` key (Bearer or Basic); missing key `401 authentication_error`; live keys refused | Keys are not validated against an account |
| Errors | Stripe's `{"error": {type, code, decline_code, message, param, doc_url}}`, 402 card errors with the PaymentIntent | |
| Request ids | `Request-Id` on every response; `event.request.id` / `request.idempotency_key` on events an API request causes; `null` for automatic events and hosted-page actions | Ids are seeded (`req_` + 14 base62) instead of random |
| Idempotency | Every `POST` with `Idempotency-Key`: same body replays the first answer (`Idempotent-Replayed: true`, `Original-Request`), a different body is `400 idempotency_error` | Keys never expire (Stripe: 24 h); concurrent use of one key is not refused with 409; validation errors are cached too |
| Customers, PaymentMethods, PaymentIntents, Charges, Refunds | CRUD, attach/detach, confirm/capture/cancel, full and partial refunds, the documented test cards and `pm_card_*` tokens | Card payment methods only |
| Products, Prices | CRUD; `POST /v1/prices` with `product` **or** `product_data[name|active|metadata]` (exactly one, as at Stripe); `recurring[interval]` validated | No `price.created` / `product.created` events; no tiers, `price_data`, lookup keys |
| Subscriptions | Create (several items, quantities), update, cancel now or at period end, `default_payment_method`, metadata; first invoice charged at once (`incomplete` when it fails); all items must share currency and interval | Fixed interval lengths (month = 30 days, year = 365 days) |
| Trials | `trial_period_days` / `trial_end` (`now` = none; mutually exclusive; ≤ 730 days) on `POST /v1/subscriptions`; `subscription_data[trial_period_days|trial_end]` on Checkout (`trial_end` ≥ 48 h ahead); `trialing`, `trial_start`, `trial_end`, `billing_cycle_anchor = trial_end`; a $0 first invoice; `customer.subscription.trial_will_end` three days before the end (at once for shorter trials); the first real invoice at `trial_end` | No `trial_settings.end_behavior` (a trial without a payment method ends `past_due`); no `pending_setup_intent` |
| Renewals and dunning | On the simulated clock: cycle invoice, payment with the default payment method, `invoice.paid` / `invoice.payment_failed`, `active` / `past_due`, period-end cancellation; `POST /v1/admin/subscriptions/{id}/renew` forces one | No Smart Retries: a failed invoice is not retried automatically, and a `past_due` subscription keeps renewing until canceled |
| Quantity changes and prorations | `POST /v1/subscription_items/{id}` (`price`, `quantity`, `metadata`, `proration_behavior`) and `items[]` on `POST /v1/subscriptions/{id}` (update by `id`, add without `id`, remove with `deleted`); prorated to the second as a credit for unused time and a charge for the remaining time; `create_prorations` (default) adds them to the next invoice, `always_invoice` invoices and collects now, `none` creates none; `GET /v1/subscription_items[/{id}]`; `customer.subscription.updated` | Pending prorations are not exposed as invoice items (`/v1/invoiceitems`, upcoming invoice not modelled). Changing the billing **interval** restarts the cycle and invoices at once, but charges the new price for the remainder of the old period instead of a full new period (0.1.x behaviour, kept for its scenario verifiers) |
| Invoices | Retrieve, list, update metadata, pay, void; `subscription_create`, `subscription_cycle`, `subscription_update` | No PDF / hosted invoice pages |
| Checkout | `mode=payment|subscription`, several `line_items` (`price` + `quantity` ≥ 1), `customer` / `customer_email`, `client_reference_id`, `metadata`, `subscription_data[metadata]` onto the subscription, `success_url` with `{CHECKOUT_SESSION_ID}` substituted, `cancel_url`, 24 h expiry with `checkout.session.expired`, `expand[]=subscription` / `customer` on retrieve; with a trial `amount_total` is 0 and `payment_status` `no_payment_required` | Only existing `price`s (no `price_data`); `expand[]` is one level deep; `line_items` are not listable |
| Hosted Checkout page | Test cards: `4242424242424242` succeeds, `4000000000000002` declines, `4000000000009995` insufficient funds, `4000002500003155` and `4000000000003220` ask for 3-D Secure on a simulated challenge page (Complete / Fail) | The challenge page is the simulator's own. A card set up with authentication (`3155`) renews off-session; `3220` asks every time, so its renewals fail (`invoice.payment_failed`; Stripe would also send `invoice.payment_action_required`) |
| Billing portal | Session create; page cancels at period end and resumes | Only those two actions |
| Events | Retrieve, list (`type`, `episode_id`); `checkout.session.completed` only after the customer and subscription exist | `*.updated` events carry no `data.previous_attributes` |
| Webhooks | Persisted outbox, `Stripe-Signature` (`t=<attempt time>,v1=HMAC-SHA256`), non-2xx and redirects are failures, live-mode retries on the simulated clock: after 1, 2, 4, 8, 16 and 32 hours (attempts at +0, +1, +3, +7, +15, +31, +63 h), then `exhausted`; replay | One endpoint, every event type. Stripe documents only "up to 3 days with an exponential back off" (live mode) and "three times over a few hours" (test mode); the intervals are the simulator's. Events emitted while no endpoint is configured are not queued later (as at Stripe). 3 s connect/read timeouts |

## Control protocol interpretations

[CONTROL-PROTOCOL.md](CONTROL-PROTOCOL.md) is canonical; where it leaves room, the simulator does this:

- **Seeds** are text. An integer seed is used as is (so 0.1.x seeds build the same worlds); other
  text is hashed with 64-bit FNV-1a.
- **Reset defaults.** A JSON (or empty) reset defaults to `clock_mode=manual`, starting at a
  deterministic time in 2027 derived from seed and scenario unless `now` is given; `now` with
  `clock_mode=free` is refused (400). Resets with query or form parameters keep 0.1.x's default
  (`free` without a scenario, `manual` with one). Reset keeps the configuration (webhook endpoint
  and `webhook_delivery`), which snapshots do carry.
- **Revision** counts every change of the world: actor writes, scheduled work, fault consumption,
  webhook attempts, configuration, reset and restore (restore sets `max(current, snapshot) + 1`).
  Advancing the request-id position (any actor request, reads included) is persisted but does not
  count as a change.
- **Clock moves** settle world work and, in `auto` delivery mode, webhook attempts, in time order;
  `settled` counts both. Webhook attempts are network calls made outside the world lock (so a
  receiver may call the API back); a move that crosses one is applied in segments, each atomic and
  persisted. A move without due callbacks is a single atomic step.
- **Signatures** carry the simulated time of the attempt; a receiver that checks the timestamp
  against its own clock has to share the simulated time.
- **Post-commit faults** replace whatever the operation answered, error answers included; the
  idempotency record keeps the real answer. Over the in-memory test engine (no socket) a drop raises
  an exception instead.
- **Flush** attempts listed ids whatever their state (a delivered job is sent again); its status is
  recomputed from that attempt. Replay answers `201`.
- **Journal** `at` is the simulated time at which the outcome was known. Response bodies are
  captured as sent. The journal lives in `<state>.journal.jsonl` beside the state file; snapshots in
  `snapshots/` beside it.
- **Errors** carry `type: "admin_error"` and, where useful, `param` besides `code` and `message`;
  `clock_not_manual` names `param: "clock_mode"`.
- **Health** and the reset answer keep 0.1.x's fields (world counts, `clock.current_time`,
  `object`, `state_revision`, `task_context`); `/v1/admin/state` keeps its established field names
  and adds `revision`, `faults`, `webhooks` and `config`.
- **Webhook secrets** are never persisted, exported or carried by snapshots.

## Upstream drift

`python3 tools/check_upstream.py --local` verifies `contracts/` offline. The weekly check compares the
modelled operations in Stripe's latest published document with the last reviewed upstream state
(`reviewed` in the manifest, initially the pinned tag) and fails when they differ. After reviewing,
either update the simulator and re-pin (`--pin <tag>`), or record the review (`--ack <tag>`).
