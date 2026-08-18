# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.1.2]: https://github.com/SiereSoft/xstripe-payment-simulator/releases/tag/v0.1.2
[0.1.1]: https://github.com/SiereSoft/xstripe-payment-simulator/releases/tag/v0.1.1
[0.1.0]: https://github.com/SiereSoft/xstripe-payment-simulator/releases/tag/v0.1.0
