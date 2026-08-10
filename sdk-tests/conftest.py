"""Shared configuration for the stripe-python end-to-end suite.

The official, unmodified stripe-python SDK is pointed at the running simulator by
setting only its two public config knobs: `api_base` and `api_key`. Everything
else is real SDK behavior.
"""
import os
import urllib.request

import pytest
import stripe

BASE = os.environ.get("FAKE_STRIPE_BASE", "http://localhost:12111")
CONTROL_BASE = os.environ.get("FAKE_STRIPE_CONTROL_BASE", "http://localhost:12112")
CONTROL_TOKEN = os.environ.get("FAKE_STRIPE_CONTROL_TOKEN", "gym_control_local")
KEY = os.environ.get("FAKE_STRIPE_KEY", "sk_test_123")

# The ONLY changes to the SDK — no patching.
stripe.api_base = BASE
stripe.api_key = KEY


@pytest.fixture(scope="session", autouse=True)
def reset_world():
    """Start every run from the same deterministic seeded world."""
    req = urllib.request.Request(
        f"{CONTROL_BASE}/v1/admin/reset?seed=1&episode_id=sdk-python",
        method="POST",
        headers={"X-Siere-Control-Token": CONTROL_TOKEN},
    )
    urllib.request.urlopen(req, timeout=10)
    yield
