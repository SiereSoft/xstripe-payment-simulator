# Contributing

Thanks for the interest. This project is maintained by [Siere Soft](https://sieresoft.com) — deliberately small and opinionated about scope. That shapes how we take contributions.

## What we're glad to take

- Bug fixes with a failing test that turns green.
- Closer fidelity to real Stripe wire shapes (field names, types, error codes) — ideally with a reference to Stripe's docs or a diff against `stripe-mock`.
- New payment-core behaviour that comes with tests and a short note on why it matters for an agent task.

## What we'll probably say no to

- Scope beyond the payments core + recurring billing (Connect, Tax, Terminal, Issuing, …). Not because it's uninteresting — because a focused simulator is more useful than a half-built copy of all of Stripe.
- Features without tests.

We'd rather ship a small thing that behaves correctly than a large thing that mostly does. If in doubt, open an issue first and we'll tell you straight whether it fits.

## Ground rules

- Every change keeps `./gradlew test` green. New behaviour ships with a test.
- Match the existing style: state transitions live in the state machine / `billing`, routes stay thin, responses go through the model `toApiJson()` builders.
- Keep responses in Stripe's exact shape — the official SDKs run against this unmodified, and that must keep working.
- By contributing, you agree your contribution is licensed under Apache-2.0 (the project license).

## Running it

```bash
./gradlew test          # unit + stripe-java SDK tests
FAKE_STRIPE_CONTROL_TOKEN=gym_control_local ./gradlew run
                         # actor on :12111, controller on loopback :12112
docker compose up --build
```

Questions: [hello@sieresoft.com](mailto:hello@sieresoft.com).
