---
title: We built a Stripe you can train agents against
subtitle: A stateful, Stripe-compatible API — because you can't teach an agent to handle money against a mock that forgets everything.
author: Siere Soft
date: 2026-07-08
tags: [ai agents, evals, open source, kotlin, payments]
canonical: https://sieresoft.com/insights/payments-gym
---

> **TL;DR** — We open-sourced **Siere Payments Gym**: a stateful, Stripe-compatible payments API that runs locally in a container. Stripe's own SDKs drive it *unmodified*. It remembers state, replays from a seed, and emits signed webhooks — so you can develop, test, and **train AI agents** against a payment backend that behaves like the real thing. Apache-2.0, on our GitHub. Not affiliated with Stripe.

## The problem

We kept hitting the same wall on agent work: how do you let an AI agent *learn* to operate a payments API without pointing it at real money?

Real Stripe is out. It needs the internet, an account, and — the dealbreaker for training — it isn't reproducible. You can't run the same episode twice and expect the same starting world.

Stripe's own `stripe-mock` is the obvious next thought. It's excellent at what it does: it reads Stripe's OpenAPI spec and returns schema-valid responses for the entire API. But it is **stateless by design**. Create a customer and it's forgotten the instant the response is sent. Confirm a PaymentIntent and nothing transitions. The card number doesn't cause a decline. A charge isn't linked to the customer you just made.

That's fine for checking that your client deserializes the right shapes. It's useless as a *world*. And training or evaluating an agent needs a world — the server has to remember what happened, enforce the rules, and let the agent's actions have consequences a checker can inspect afterwards.

So we built the world.

## What it is

**Siere Payments Gym** is a stateful simulator of Stripe's payments core, written in Kotlin (Ktor), shipped as a container. One command:

```bash
docker compose up --build      # API on http://localhost:12111
```

It covers the payments core end to end:

- the full **PaymentIntent state machine** — `requires_payment_method → requires_confirmation → requires_action → processing → succeeded / requires_capture / canceled`, driven by Stripe's documented test cards (a `4242…` succeeds, a `4000…0002` declines, a 3DS card asks for action);
- **refunds** (full and partial), **idempotency keys**, the **product/price catalog**, **subscriptions with proration** on upgrade, and **invoices**;
- **signed webhooks** — it POSTs events to your endpoint with a real `Stripe-Signature: t=…,v1=…` header, the exact HMAC scheme `stripe.Webhook.constructEvent` verifies;
- Stripe's exact error envelope, so a declined charge throws a `CardError` in your SDK, not a mystery 500.

And the two things a mock won't give you:

**It remembers.** Create a customer, restart the container, and he's still there. State lives in memory and snapshots to disk on every mutation.

**It's reproducible.** `POST /v1/admin/reset?seed=42` builds the same customers, saved cards, and payment history every time — down to the object IDs. That determinism is the whole point of a training environment.

## The test we care about

Anyone can claim "Stripe-compatible." Here's our receipt: **Stripe's own official client libraries drive it, unmodified.** The only change is the base URL — a first-class configuration option in every Stripe SDK, built for exactly this kind of testing. Nothing is patched.

```python
import stripe
stripe.api_base = "http://localhost:12111"   # the only change
stripe.api_key  = "sk_test_anything"

pi = stripe.PaymentIntent.create(
    amount=2000, currency="usd", payment_method="pm_card_visa", confirm=True,
)
assert pi.status == "succeeded"
```

If our response shapes were wrong, the SDK would fail to build its typed objects. It doesn't. The repo ships this proof as tests: **stripe-java runs in the Gradle suite; stripe-python runs as a pytest suite** — covering customers, confirms, declines, refunds, subscriptions, and idempotency. All green.

## Why it's a *gym*, not a mock

The loop is: **seed → act → verify.** Seed a known world. Let an agent act — "refund the smaller of the two charges and leave the other untouched," "upgrade this customer from Basic monthly to Pro annual and confirm the new invoice total." Then inspect the state and score it. Because the world is deterministic and stateful, a checker can assert *exactly* what should have changed, and nothing else.

That's the part `stripe-mock` can't do, and it's the part that matters if you're building agents that touch real systems.

## What's next

This is the first of a small set of environments we're opening up. Next on this one: a packaged **task pack** with graded checkers (so it ships as a benchmark, not just an API), an **MCP layer** so agents can call it as tools, and a worked training run with a score curve.

The one we're most excited about is a companion: an **Android agent gym**, where an agent drives a real mobile checkout *UI* and the outcome is verified in this payments backend. Cross-layer, UI-to-database, and — as far as we can tell — the first open environment of its kind. Mobile is our home turf, so it should be.

## Who made this

We're **[Siere Soft](https://sieresoft.com)** — a European software studio. Three senior engineers, spec-first, AI-native. We build products (our live one, [Siere AOE](https://siere.ai), makes your site legible to AI agents) and, increasingly, the verifiable simulated worlds that agent work depends on.

If you're building agents, evals, or payments and want senior help — [book a call](https://sieresoft.com/contact). If you just want to poke at it, the repo's right here, Apache-2.0.

*Not affiliated with, or endorsed by, Stripe, Inc. "Stripe" is a trademark of Stripe, Inc. This is an independent, clean-room simulator for testing — never for real payments.*
