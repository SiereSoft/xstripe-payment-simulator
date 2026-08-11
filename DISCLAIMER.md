# Disclaimer

Read this before running the simulator.

## This is not Stripe, and it is not a payment processor

This project is an independent simulator that imitates the observable behaviour of
Stripe's public HTTP API, written from Stripe's public documentation. It is **not
affiliated with, endorsed by, or sponsored by Stripe, Inc.** "Stripe" and the Stripe
API are trademarks of Stripe, Inc.

It **processes no payments**. No money moves. Nothing it does has any effect on any
real account at Stripe or anywhere else. It must never be placed in a payment flow
that a real customer can reach.

## Never give it real data

**Do not enter a real card number.** The hosted checkout page accepts card numbers
and the simulator stores what it is given in a plaintext JSON snapshot on disk
(`FAKE_STRIPE_DATA`, by default `data/state.json`). There is no encryption, no
tokenisation, and no key management. The same applies to real names, addresses,
email addresses, credentials, API keys, and any other personal or production data.

Use the documented test cards (see the README) and obviously fake customer details.

**If you accidentally send it a live-mode API key, rotate that key.** The simulator
refuses `sk_live_`/`rk_live_` credentials precisely so this fails loudly, but a key
that reached an unintended server should be treated as exposed.

## It is not hardened

This is a development tool. Specifically:

- **Authentication is simulated.** Any test-mode key is accepted; the simulator does
  not know or care who you are. Rejecting a *missing* key exists to reproduce Stripe's
  `401`, not to protect anything.
- **There is no authorization.** Any caller who can reach the API can read and modify
  every object in the world.
- **There is no rate limiting**, and request bodies are not size-capped. Event and
  idempotency history grows for the lifetime of a snapshot.
- **State snapshots are plaintext** and written on every mutation.
- **It is not PCI DSS compliant** and makes no claim to be.

Both listeners bind to loopback by default. Binding wider (`HOST=0.0.0.0`) exposes
the entire simulated world, and the controller plane if enabled, to everyone who can
reach that interface. Do not expose this service to the public internet or to any
untrusted network.

The controller/verifier API is a separate listener behind its own token. Never expose
that token to the system under test.

## No warranty

Licensed under Apache-2.0. As stated in sections 7 and 8 of the licence, the software
is provided **"AS IS", without warranties or conditions of any kind**, and the
contributors are not liable for any damages arising from its use.

In particular there is **no guarantee of complete or correct Stripe compatibility**.
The simulator reproduces a subset of Stripe's payments core and deliberately diverges
in places (see "Honest limitations" in the README). Behaviour that matches Stripe
today may not match after Stripe changes, and passing against this simulator is not
evidence that an integration will work against Stripe.

## Reporting problems

Security issues: see [SECURITY.md](SECURITY.md). Everything else: open an issue.
