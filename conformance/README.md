# Conformance: proving equivalence with real Stripe

The [SDK compatibility tests](../sdk-tests) prove the official SDKs can *drive* the
simulator. This goes further: it proves the simulator **returns the same thing real
Stripe does**, field by field.

The technique is **record & replay**. We run identical flows through the official
`stripe-python` SDK against two backends — real Stripe **test mode** and the
simulator — normalize away the volatile bits (ids, timestamps, secrets, urls), and
diff what remains. Because it's the same SDK and the same flows, any difference is a
real difference in behavior or wire shape.

## Get a Stripe test key (free, no business verification)

1. Create a Stripe account (or use an existing one).
2. Dashboard → **Developers → API keys** → copy the **test mode** secret key
   (`sk_test_…`). Test mode moves no real money and needs no verification.

That's the only external thing required.

## Run it

```bash
pip install -r ../sdk-tests/requirements.txt
docker compose up --build          # start the simulator (in the repo root)

# 1) Record real Stripe's responses into golden files (once):
REF_KEY=sk_test_xxx python compare.py capture

# 2) Check the simulator against them (repeatable, no account needed after capture):
python compare.py verify

# …or do both at once:
REF_KEY=sk_test_xxx python compare.py live
```

`verify` exits non-zero if the simulator diverges, so it drops straight into CI once
you commit the golden files.

## Reading the output

Each flow reports three kinds of difference:

| Kind | Meaning | What to do |
|---|---|---|
| `missing_in_sim` | real Stripe returns a field we don't | add the field for closer fidelity |
| `value_mismatch` | a stable field has a different value | a behavioral difference — investigate |
| `extra_in_sim` | we return a field real Stripe doesn't | usually harmless (reported as a warning) |

`missing_in_sim` and `value_mismatch` count as failures; extras and list-length
differences are warnings. This turns "is it really like Stripe?" into a punch list.

## No account? Two weaker-but-free checks

- **Self-check the harness:** point the reference at the simulator itself —
  `REF_BASE=http://localhost:12111 REF_KEY=sk_test_123 python compare.py live` — and
  you should get zero diffs. Proves the machinery, not the fidelity.
- **stripe-mock shape diff:** run [`stripe-mock`](https://github.com/stripe/stripe-mock)
  on `:12113` and set `REF_BASE=http://localhost:12113 REF_KEY=sk_test_x`. `stripe-mock`
  is schema-accurate (generated from Stripe's OpenAPI spec) but stateless, so treat
  value mismatches as expected and focus on **shape** (`missing_in_sim`).

## Normalization (what we ignore on purpose)

Dropped: `created` and every `*_at` timestamp, period boundaries, `client_secret`
and `*_secret`, `balance_transaction`, `fingerprint`, receipt/invoice URLs,
human-facing `number`/`invoice_prefix`, and request metadata. Any value that looks
like a Stripe id (`cus_…`, `pi_…`, `…_secret_…`) is collapsed to `<id>` so structure
compares without the random part. Everything else — amounts, currencies, statuses,
enums, booleans, quantities, intervals, error codes — is compared exactly.

## Known, intentional divergences

- **Proration amounts** on a mid-cycle upgrade use fixed interval lengths (30-day
  month, 365-day year), not calendar-exact dates — so a proration total will differ
  from real Stripe by the day-count. This is deliberate (it keeps totals predictable
  for task checkers); the upgrade flow is therefore not in the default flow set.
- Real Stripe returns many fields for peripheral features we don't model (tax,
  test clocks, pending updates, …). Those show up as `missing_in_sim` and form the
  fidelity backlog.
