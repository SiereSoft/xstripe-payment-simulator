# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] — 2026-10-02

### Added

- The Siere simulator control protocol, version 1 (`docs/CONTROL-PROTOCOL.md`, a verbatim copy of
  the canonical text): health with `simulator`, `version`, `protocol`, `seed`, `revision` and
  `journal_seq`; `X-Siere-Control-Protocol: 1` on every controller response; JSON resets with text
  seeds, `clock_mode` and `now`; `GET`/`POST /v1/admin/clock` and `POST /v1/admin/clock/advance`
  with time-ordered settling; named faults (`error`, `commit_then_error`, `commit_then_drop`,
  `drop_before` — a real closed connection — and `latency`) matched by operation or method and
  path pattern, with `skip`/`count`; the event journal (`GET /v1/admin/journal`); named snapshots;
  `GET`/`PATCH /v1/admin/config`. `ControlProtocolTest` checks section 9 over the real listeners.
- Seeded, deterministic request ids in `Request-Id`, recorded in the journal and stamped on events
  as `request.id` with the request's `request.idempotency_key`; replays answer `Original-Request`.
- A persisted webhook outbox: no event is dropped, failed attempts are retried on the simulated
  clock after 1, 2, 4, 8, 16 and 32 hours (Stripe's live-mode "up to 3 days with an exponential back
  off"), then `exhausted`; `auto` or `manual` delivery, `GET /v1/admin/webhooks`, flush (ids,
  reverse, concurrency, include_future) and replay; one journal entry per attempt.
- Scheduled work on the simulated clock: subscription renewals with invoice, payment and dunning,
  trial ends, `customer.subscription.trial_will_end`, Checkout Session expiry
  (`checkout.session.expired`) and webhook retries, in time order.
- Trials (`trial_period_days`, `trial_end`, `subscription_data[trial_*]` on Checkout), several
  Checkout line items as subscription items, `subscription_data[metadata]`,
  `POST /v1/subscription_items/{id}` and `items[]` updates with Stripe's proration behaviours,
  `GET /v1/subscription_items`, prices with inline `product_data`, and a simulated 3-D Secure page
  on hosted Checkout.
- `docs/AGENT-GUIDE.md`, `docs/COMPATIBILITY.md`, a pinned Stripe OpenAPI subset for API 2024-06-20
  in `contracts/` with `tools/check_upstream.py` and a weekly upstream workflow, committed
  conformance recordings replayed in CI, and a Kover line-coverage gate (90 %) in `./gradlew check`.

### Changed

- Without a usable `FAKE_STRIPE_CONTROL_TOKEN` (at least 16 characters) the controller port is not
  opened at all (0.1.x served it and answered 404); the log says why.
- Moving a free clock answers `409 clock_not_manual` (was 400); a JSON or empty reset defaults to a
  manual clock (query/form resets keep 0.1.x's default).
- The scenario `duplicate_payments_refund_response_loss` installs an ordinary named fault
  (`refund-response-loss`); `/v1/admin/state` adds `revision`, `faults`, `webhooks`, `config` and
  keeps `fault_injection` as a view of it.
- Subscription item changes within one billing interval follow Stripe's `proration_behavior`
  (`create_prorations` waits for the next invoice instead of invoicing at once).
- `success_url` accepts Stripe's `{CHECKOUT_SESSION_ID}` template (0.1.x rejected the braces).
- The version has one source (`build.gradle.kts`); Gradle runs on JDK 21 and compiles and tests on a
  JDK 11 toolchain.

## [0.1.2] — 2026-08-18

### Security

- Update Logback from 1.4.14 to 1.6.1, outside the affected range for
  GHSA-25qh-j22f-pwp8 (CVE-2025-11226).

## [0.1.1] — 2026-08-18

### Added

- Deterministic after-commit response-loss injection and a matching duplicate-payment recovery
  scenario, so clients can prove idempotent retries rather than only normal success.
- A collateral-subscription development probe that proves target completion cannot offset an
  unrelated subscription mutation.
- A tag-driven GHCR release workflow that tests the tagged source, builds `linux/amd64` and
  `linux/arm64`, publishes immutable OCI digests, adds an artifact attestation, and creates the
  matching GitHub release.

### Changed

- A blank controller token now disables the privileged controller instead of accidentally
  enabling it with an empty credential.
- Gradle and JRE container bases are digest-pinned, archive ordering and timestamps are stable,
  and the same source epoch produces byte-reproducible local images.

## [0.1.0] — 2026-08-12

First public release.

### Added

- **Payments core** — Customers, PaymentMethods, PaymentIntents with a real status
  state machine, Charges, and Refunds (partial, full, and over-refund rejection).
- **Test cards drive outcomes** — success, decline with `decline_code`, manual
  capture, and authentication-required, matching Stripe's documented test numbers.
- **Recurring billing** — Products, Prices, Subscriptions with proration on upgrade,
  Invoices, on-demand renewal, and dunning (`past_due` + `invoice.payment_failed`).
- **Hosted pages** — Checkout Session and customer billing portal, rendered as plain
  server-side HTML that redirects the way Stripe's do.
- **Signed webhooks and Events** — `Stripe-Signature: t=…,v1=…` HMAC-SHA256 over
  `"<timestamp>.<payload>"`, verifiable by `stripe.Webhook.constructEvent`.
- **Idempotency keys** — first response replayed on repeat, conflict on a reused key
  with a different body, surviving restarts.
- **Deterministic seeding** — the same seed reproduces the same world down to object
  IDs, with the ID generator's position persisted across restarts.
- **Simulated clock** — `free` follows wall time; `manual` starts at a deterministic
  seeded time and is advanced only through the controller.
- **Separate controller listener** — Gym lifecycle, reset with scenarios, episode
  correlation, and a privileged redacted full-state export, all behind their own
  token on their own port, never registered on the actor listener.
- **Snapshot persistence** — state is written after every mutation and reloaded at
  startup, with a monotonic `state_revision`.
- Official SDK test suites (`stripe-java`, `stripe-python`) running unmodified,
  a Postman collection with its generator, and a conformance comparison tool.

### Security

- Business-path detection is based on the decoded request path, so a
  percent-encoded URL such as `/%76%31/customers` can no longer reach a business
  route while skipping the API-key check.
- `success_url`, `cancel_url`, and `return_url` are restricted to `http`/`https` at
  session-create time, closing a `javascript:` injection into the hosted pages'
  form actions and `Location` headers, and an open redirect.
- Both listeners default to `127.0.0.1`. Binding wider is now an explicit choice.
- Live-mode credentials (`sk_live_`, `rk_live_`, `pk_live_`) are refused.
- `GET /v1/admin/reset` removed; reset is POST-only.
- The controller webhook endpoint reports `secret_configured` instead of echoing the
  signing secret.
- Webhook delivery refuses non-http(s) schemes, embedded credentials, and
  link-local/multicast/wildcard destinations, does not follow redirects, uses a
  bounded queue, and logs only the destination origin.
- Controller token comparison is constant-time over fixed-width digests.
- Unhandled exceptions log server-side and return a fixed message rather than
  echoing internal detail.
- Hosted pages send a strict CSP plus `X-Content-Type-Options`, `Referrer-Policy`,
  and `Cache-Control: no-store`.
- Netty constrained to 4.1.136.Final via BOM, clearing the advisories carried by the
  version Ktor 2.3.12 resolves by default.
- Seeded scenario objects no longer carry a `gym_role` metadata field. Target and
  distractor identification is verifier knowledge and lives in `verifierContext`,
  reachable only through the controller — an agent can no longer read the answer out
  of the actor-facing API.

[0.2.0]: https://github.com/SiereSoft/xstripe-payment-simulator/releases/tag/v0.2.0
[0.1.2]: https://github.com/SiereSoft/xstripe-payment-simulator/releases/tag/v0.1.2
[0.1.1]: https://github.com/SiereSoft/xstripe-payment-simulator/releases/tag/v0.1.1
[0.1.0]: https://github.com/SiereSoft/xstripe-payment-simulator/releases/tag/v0.1.0
