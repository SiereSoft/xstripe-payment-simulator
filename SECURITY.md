# Security Policy

## Reporting a vulnerability

Please report security issues privately to **hello@sieresoft.com**, or via GitHub's
[private vulnerability reporting](https://github.com/SiereSoft/xstripe-payment-simulator/security/advisories/new)
on this repository.

Please do not open a public issue for a security problem.

Include what you need to make the issue reproducible: the endpoint, a request that
triggers it, what you expected, and what happened. A proof of concept helps but is
not required.

**What to expect:** acknowledgement within 5 working days, an assessment with a fix
or a rejection within 30 days, and credit in the release notes if you want it.

## Supported versions

Only the `main` branch is supported. This project has no long-term support branches.

## Threat model — please read before reporting

This is a **local development and testing tool**, not production infrastructure. Its
security posture is deliberately shallow in ways that would be bugs in a real payment
system. The following are **known and out of scope**, and reports about them will be
closed as working-as-intended:

- **Any test-mode API key is accepted.** The simulator does not authenticate callers.
  A missing key returns `401` only to reproduce Stripe's error shape.
- **There is no authorization between objects.** Any caller can read or modify any
  object in the simulated world.
- **No rate limiting**, no request-body size cap, and unbounded growth of event and
  idempotency history over the lifetime of a snapshot.
- **State snapshots are plaintext JSON on local disk**, including card numbers typed
  into the hosted checkout page. See [DISCLAIMER.md](DISCLAIMER.md); do not enter real
  card data. (Removing PAN persistence entirely is planned.)
- **Webhook delivery reaches a controller-configured URL.** The dispatcher refuses
  non-http(s) schemes, embedded credentials, and link-local/multicast/wildcard
  addresses, and does not follow redirects — but loopback and private-range targets
  are allowed by design, because a local receiver is the normal use case.
  Configuring the target requires the controller token.
- **Exposing the service on a non-loopback interface** exposes everything above. Both
  listeners default to `127.0.0.1`; widening that is the operator's decision.
- **Deterministic, seed-derived object IDs.** Reproducibility is the point of this
  project. Session IDs are predictable to anyone who knows the seed.

## In scope

Reports that are very much wanted:

- Anything that bypasses the **controller/actor split** — reaching a `/v1/admin/`
  route on the actor listener, or invoking one without a valid controller token.
- Anything that lets the **system under test discover verifier state** it should not
  see (scenario answers, `verifierContext`, controller-only IDs), since that silently
  invalidates evaluations.
- **Injection into the hosted pages** — HTML, script, or header injection reachable
  from an API parameter or form field.
- **Redirect abuse** — a non-http(s) scheme or an unintended redirect target reaching
  a `Location:` header or a form action.
- **Path or auth-check bypasses**, such as encoding tricks that make a business route
  skip the API-key check.
- **Secret leakage** — a webhook signing secret, control token, or raw idempotency key
  appearing in a response, a log line, or the state export.
- Anything that lets a request **escape the simulator's own data directory** or write
  outside `FAKE_STRIPE_DATA`.
- Vulnerable dependencies with a demonstrable path to exploitation here.

## Hardening for shared environments

If you run this anywhere more exposed than your own machine:

- Keep `HOST` and `CONTROL_HOST` on loopback, or put the service behind an
  authenticating proxy.
- Set a random `FAKE_STRIPE_CONTROL_TOKEN` — never the sample value from
  `compose.yaml`:
  ```bash
  FAKE_STRIPE_CONTROL_TOKEN=$(openssl rand -hex 24) docker compose up
  ```
- Point `FAKE_STRIPE_DATA` at a directory only the service account can read.
- Treat the snapshot as sensitive and delete it when you are done.
