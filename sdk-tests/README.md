# SDK compatibility tests (stripe-python)

These run the **official, unmodified `stripe-python` SDK** against the simulator — the "killer test" from the project brief. The only changes to the SDK are its two public config knobs (`api_base`, `api_key`); nothing is patched.

The Java equivalent lives in the main Gradle test suite (`StripeJavaSdkTest`) and runs with `./gradlew test`.

## Run

Start the simulator, then run the suite against it:

```bash
# 1. Start the server (either way)
docker compose up --build            # from the repo root
# ...or: FAKE_STRIPE_CONTROL_TOKEN=gym_control_local ./gradlew run

# 2. In another shell, run the SDK tests
cd sdk-tests
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest -v
```

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `FAKE_STRIPE_BASE` | `http://localhost:12111` | Simulator base URL |
| `FAKE_STRIPE_CONTROL_BASE` | `http://localhost:12112` | Host-only controller base URL used by the reset fixture |
| `FAKE_STRIPE_CONTROL_TOKEN` | `gym_control_local` | Controller credential sent only by the reset fixture |
| `FAKE_STRIPE_KEY` | `sk_test_123` | Any `sk_...` value; the simulator accepts any key |

The suite resets the world to `seed=1` with episode ID `sdk-python` at the start
of the session (`conftest.py`).

## What it covers

Customer create, PaymentIntent create + confirm (success), retrieve round-trip, manual capture, list, and a declined card raising the SDK's typed `CardError`. Automated assertions live in `test_flows.py`; later these become part of the broader validation suite.
