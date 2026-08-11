## What this changes

<!-- One or two sentences. If it fixes an issue, link it. -->

## Why

<!-- What was wrong, or what does this enable? For fidelity fixes, link the
     Stripe documentation that establishes the correct behaviour. -->

## Checklist

- [ ] `./gradlew test` passes.
- [ ] New behaviour ships with a test (a bug fix ships with the failing test that now passes).
- [ ] Response shapes still match Stripe's — the official SDKs run against this
      unmodified, and that must keep working.
- [ ] No new secret, token, or personal data in code, tests, fixtures, or logs.
- [ ] Nothing that reveals scenario answers on the actor-facing API (verifier
      knowledge belongs in `verifierContext`, behind the controller token).
- [ ] Endpoint changed? The Postman generator (`postman/generate_collection.py`)
      was updated and re-run.
- [ ] I agree my contribution is licensed under Apache-2.0.
