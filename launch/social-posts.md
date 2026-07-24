# Launch copy

Drafts for the launch. Replace `REPO_URL` with the public repo (likely
`https://github.com/SiereSoft/xstripe-payment-simulator`) and `BLOG_URL` with the
Insights post. Post the blog first, then HN, then LinkedIn/X pointing at whichever
did best. Voice: dry, confident, receipts-first — no hype words.

---

## Hacker News (Show HN)

**Title**
```
Show HN: Siere Payments Gym – a stateful, Stripe-compatible API for training agents
```
(Keep it under 80 chars. No "revolutionary", no emojis — HN allergic to both.)

**First comment (post immediately after submitting)**
```
Hi HN — I run a small EU studio (Siere Soft). We do a lot of AI-agent work, and we
kept needing a payments backend an agent could actually learn against. Real Stripe
isn't reproducible; stripe-mock is stateless by design (create a customer, it's
gone next call). Neither works as a training/eval environment.

So we built the stateful version. It's a Stripe-compatible payments API in Kotlin
(Ktor), one `docker compose up`. It covers the PaymentIntent state machine,
refunds, idempotency, products/prices, subscriptions with proration, invoices, and
signed webhooks. Two things a mock won't do: it remembers state across restarts,
and `reset?seed=N` rebuilds an identical world every time (down to the IDs) — which
is the whole point for training.

The claim we actually care about: Stripe's own SDKs drive it unmodified — only the
base URL changes. If our shapes were wrong the SDKs wouldn't deserialize. That
proof ships as tests (stripe-java in Gradle, stripe-python in pytest).

Apache-2.0. Not affiliated with Stripe — it's a clean-room simulator for testing,
never real payments. Roadmap: a graded task pack, an MCP tool layer, and a
companion Android agent gym (agent drives a real checkout UI, verified in this
backend).

Repo: REPO_URL
Write-up: BLOG_URL

Happy to answer anything about the design — especially the proration and webhook
signing, which were the fiddly bits.
```

---

## LinkedIn (company page + founders reshare)

```
You can't teach an AI agent to handle money against a mock that forgets everything.

We kept hitting that wall. Real Stripe isn't reproducible; Stripe's own stripe-mock
is stateless by design. Neither works as a place to *train* an agent.

So we open-sourced the missing piece: Siere Payments Gym — a stateful,
Stripe-compatible payments API you run locally. It remembers what happened, rebuilds
an identical world from a seed, emits signed webhooks, and handles the real payments
core: the PaymentIntent lifecycle, refunds, subscriptions with proration, invoices,
idempotency.

The receipt we're proud of: Stripe's own official SDKs drive it unmodified — we only
change the base URL. That proof ships as tests.

Why we're giving it away: this is the work our studio is built on — verifiable
simulated worlds for AI. The best way to show you can build them is to build one in
the open.

Apache-2.0, link in comments. Next up: a graded task pack, an MCP layer, and a
companion Android agent gym where an agent drives a real mobile checkout UI.

If you're building agents, evals, or payments and want senior help → sieresoft.com

(Not affiliated with Stripe. It's a simulator for testing, never real payments.)
```

**First comment:** `Repo: REPO_URL · Write-up: BLOG_URL`

---

## X / Twitter thread

```
1/ You can't train an AI agent to handle money against a mock that forgets
everything.

Real Stripe isn't reproducible. stripe-mock is stateless.

So we built + open-sourced the missing piece: a stateful, Stripe-compatible
payments API you can train agents against. 🧵

2/ Siere Payments Gym runs with one `docker compose up`.

It remembers state across restarts, and `reset?seed=N` rebuilds an identical world
every time — same customers, same IDs. That determinism is the whole point of a
training environment.

3/ It's the real payments core, not a facade:
– full PaymentIntent state machine (declines, 3DS, manual capture)
– refunds, idempotency keys
– products/prices, subscriptions with proration, invoices
– signed webhooks (real Stripe-Signature HMAC)

4/ The receipt: Stripe's own SDKs drive it *unmodified*. Only the base URL changes.

If our response shapes were wrong, the SDKs wouldn't deserialize. They do — and it
ships as tests (stripe-java + stripe-python).

5/ Why give it away? Our studio is built on making verifiable simulated worlds for
AI. Best way to prove you can → do it in the open.

Apache-2.0. Not affiliated with Stripe; it's for testing, never real money.

Repo: REPO_URL

6/ Next: a graded task pack, an MCP tool layer, and a companion Android agent gym —
an agent driving a real mobile checkout UI, verified in this backend.

We build agents, evals + payments for a living: sieresoft.com
```

---

## Directory / listing submissions (low effort, compounding)

- Submit to `awesome-ai-agents`, `awesome-llm-apps`, and agent-eval / RL-environment lists (PRs).
- Post in relevant subreddits: r/LocalLLaMA, r/MachineLearning (as a tool, not an ad), r/Kotlin (the Ktor angle).
- Dev.to / Hashnode cross-post of the blog (canonical link back to sieresoft.com).
- Mention in any EU AI community / Slack / Discord you're part of — lead with the problem, not the company.
