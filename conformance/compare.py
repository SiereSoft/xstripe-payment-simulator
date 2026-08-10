#!/usr/bin/env python3
"""Conformance harness: prove the simulator behaves like real Stripe.

It runs the SAME flows (via the official stripe-python SDK) against two backends —
a *reference* (real Stripe test mode, or stripe-mock) and the *simulator* — then
normalizes away volatile fields (ids, timestamps, secrets, urls) and diffs what's
left. Differences fall into three buckets:

  - missing_in_sim  : real Stripe returns a field we don't  -> add it for fidelity
  - value_mismatch  : a stable field has a different value   -> behavioral difference
  - extra_in_sim    : we return a field real Stripe doesn't  -> usually harmless

Modes:
  capture   run flows against the reference, save normalized golden files
  verify    run flows against the sim, diff against the saved golden files
  live      run flows against reference AND sim in one go, diff directly

Config (env):
  REF_BASE   reference base URL   (default https://api.stripe.com)
  REF_KEY    reference API key    (a Stripe *test* key sk_test_... ; any value for stripe-mock)
  SIM_BASE   simulator base URL   (default http://localhost:12111)
  SIM_KEY    simulator API key    (default sk_test_123)
  CONTROL_BASE controller base URL (default http://localhost:12112)
  FAKE_STRIPE_CONTROL_TOKEN controller credential (default gym_control_local)

Examples:
  # 1) record real Stripe's behavior (needs a free test key), then check the sim:
  REF_KEY=sk_test_xxx python compare.py capture
  python compare.py verify

  # 2) one-shot live comparison:
  REF_KEY=sk_test_xxx python compare.py live

  # 3) self-check the harness with no account (sim as its own reference):
  REF_BASE=http://localhost:12111 REF_KEY=sk_test_123 python compare.py live
"""
import json
import os
import re
import sys

import stripe

try:
    from stripe.error import CardError
except Exception:  # SDK version shim
    from stripe import CardError

REF_BASE = os.environ.get("REF_BASE", "https://api.stripe.com")
REF_KEY = os.environ.get("REF_KEY", "")
SIM_BASE = os.environ.get("SIM_BASE", "http://localhost:12111")
CONTROL_BASE = os.environ.get("CONTROL_BASE", "http://localhost:12112")
CONTROL_TOKEN = os.environ.get("FAKE_STRIPE_CONTROL_TOKEN", "gym_control_local")
SIM_KEY = os.environ.get("SIM_KEY", "sk_test_123")

GOLDEN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "golden")

ID_RE = re.compile(r"^[a-z][a-z0-9]{0,30}_[A-Za-z0-9]{6,}$")
DROP_KEYS = {
    "created", "period_start", "period_end", "current_period_start", "current_period_end",
    "start_date", "billing_cycle_anchor", "trial_start", "trial_end", "client_secret",
    "balance_transaction", "fingerprint", "receipt_url", "hosted_invoice_url", "invoice_pdf",
    "url", "number", "invoice_prefix", "receipt_number", "request", "idempotency_key",
    "financial_connections", "next_action", "webhooks_delivered_at",
}


# --- flows: each returns {label: object}; sequences chain within one backend -----

def flow_customer():
    c = stripe.Customer.create(email="jane@example.com", name="Jane Doe")
    return {"create": c, "retrieve": stripe.Customer.retrieve(c.id)}


def flow_payment_success():
    pi = stripe.PaymentIntent.create(
        amount=2000, currency="usd", payment_method="pm_card_visa", confirm=True
    )
    return {"payment_intent": pi, "charge": stripe.Charge.retrieve(pi.latest_charge)}


def flow_payment_decline():
    try:
        stripe.PaymentIntent.create(
            amount=500, currency="usd", payment_method="pm_card_chargeDeclined", confirm=True
        )
        return {"error": {"raised": False}}
    except CardError as e:
        err = e.error
        pi = getattr(err, "payment_intent", None)
        return {"error": {
            "raised": True,
            "http_status": e.http_status,
            "type": err.type,
            "code": err.code,
            "decline_code": getattr(err, "decline_code", None),
            "payment_intent_status": (pi.status if pi else None),
        }}


def flow_refund():
    pi = stripe.PaymentIntent.create(
        amount=2000, currency="usd", payment_method="pm_card_visa", confirm=True
    )
    refund = stripe.Refund.create(charge=pi.latest_charge, amount=500)
    return {"refund": refund, "charge_after": stripe.Charge.retrieve(pi.latest_charge)}


def flow_subscription():
    c = stripe.Customer.create(email="sub@example.com")
    pm = stripe.PaymentMethod.attach("pm_card_visa", customer=c.id)
    stripe.Customer.modify(c.id, invoice_settings={"default_payment_method": pm.id})
    prod = stripe.Product.create(name="Gold Plan")
    price = stripe.Price.create(
        product=prod.id, currency="usd", unit_amount=1500, recurring={"interval": "month"}
    )
    sub = stripe.Subscription.create(customer=c.id, items=[{"price": price.id}])
    return {"subscription": sub, "invoice": stripe.Invoice.retrieve(sub.latest_invoice)}


FLOWS = {
    "customer": flow_customer,
    "payment_success": flow_payment_success,
    "payment_decline": flow_payment_decline,
    "refund": flow_refund,
    "subscription": flow_subscription,
}


# --- normalization + diff --------------------------------------------------------

def to_plain(o):
    if hasattr(o, "to_dict_recursive"):
        return o.to_dict_recursive()
    if isinstance(o, dict):
        return {k: to_plain(v) for k, v in o.items()}
    if isinstance(o, list):
        return [to_plain(x) for x in o]
    return o


def normalize(v):
    if isinstance(v, dict):
        out = {}
        for k, val in v.items():
            if k in DROP_KEYS or k.endswith("_at") or k.endswith("_secret"):
                continue
            out[k] = normalize(val)
        return out
    if isinstance(v, list):
        return [normalize(x) for x in v]
    if isinstance(v, str):
        if "_secret_" in v or ID_RE.match(v):
            return "<id>"
        return v
    return v


def diff(ref, sim, path=""):
    out = []
    if isinstance(ref, dict) and isinstance(sim, dict):
        for k in ref:
            if k not in sim:
                out.append(("missing_in_sim", f"{path}.{k}", ref[k]))
            else:
                out += diff(ref[k], sim[k], f"{path}.{k}")
        for k in sim:
            if k not in ref:
                out.append(("extra_in_sim", f"{path}.{k}", sim[k]))
    elif isinstance(ref, list) and isinstance(sim, list):
        if len(ref) != len(sim):
            out.append(("length_mismatch", path, f"ref={len(ref)} sim={len(sim)}"))
        for i in range(min(len(ref), len(sim))):
            out += diff(ref[i], sim[i], f"{path}[{i}]")
    elif ref != sim:
        out.append(("value_mismatch", path, f"ref={ref!r} sim={sim!r}"))
    return out


# --- backend runner --------------------------------------------------------------

def run_backend(base, key):
    stripe.api_base = base
    stripe.api_key = key
    result = {}
    for name, fn in FLOWS.items():
        try:
            result[name] = {label: normalize(to_plain(obj)) for label, obj in fn().items()}
        except Exception as e:  # noqa: BLE001 - surface, don't crash the run
            result[name] = {"_flow_error": f"{type(e).__name__}: {e}"}
    return result


def reset_sim():
    import urllib.request
    try:
        urllib.request.urlopen(
            urllib.request.Request(
                f"{CONTROL_BASE}/v1/admin/reset?seed=1",
                method="POST",
                headers={"X-Siere-Control-Token": CONTROL_TOKEN},
            ),
            timeout=10,
        )
    except Exception:
        pass


# --- modes -----------------------------------------------------------------------

def report(reference, sim_result):
    FAIL = {"missing_in_sim", "value_mismatch"}
    total_fail = total_warn = 0
    for flow in FLOWS:
        ref = reference.get(flow, {})
        got = sim_result.get(flow, {})
        flow_diffs = []
        for label in ref:
            flow_diffs += diff(ref[label], got.get(label, {}), f"{flow}.{label}")
        fails = [d for d in flow_diffs if d[0] in FAIL]
        warns = [d for d in flow_diffs if d[0] not in FAIL]
        total_fail += len(fails)
        total_warn += len(warns)
        status = "OK  " if not fails else "DIFF"
        print(f"[{status}] {flow}: {len(fails)} diffs, {len(warns)} warnings")
        for kind, path, detail in fails[:25]:
            print(f"        {kind:16} {path}  ({detail})")
        for kind, path, detail in warns[:10]:
            print(f"        · {kind:14} {path}")
    print(f"\nTotal: {total_fail} behavioral/shape diffs, {total_warn} warnings")
    return total_fail


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "verify"

    if mode == "capture":
        if not REF_KEY:
            sys.exit("REF_KEY is required for capture (a Stripe test key sk_test_...).")
        os.makedirs(GOLDEN_DIR, exist_ok=True)
        ref = run_backend(REF_BASE, REF_KEY)
        for flow, data in ref.items():
            with open(os.path.join(GOLDEN_DIR, f"{flow}.json"), "w") as f:
                json.dump(data, f, indent=2, sort_keys=True)
        print(f"Captured {len(ref)} flows from {REF_BASE} -> {GOLDEN_DIR}/")
        return

    if mode == "verify":
        reference = {}
        for flow in FLOWS:
            p = os.path.join(GOLDEN_DIR, f"{flow}.json")
            if os.path.exists(p):
                reference[flow] = json.load(open(p))
        if not reference:
            sys.exit(f"No golden files in {GOLDEN_DIR}/. Run `capture` first.")
        reset_sim()
        sim_result = run_backend(SIM_BASE, SIM_KEY)
        sys.exit(1 if report(reference, sim_result) else 0)

    if mode == "live":
        if not REF_KEY:
            sys.exit("REF_KEY is required for live (a Stripe test key, or any value for stripe-mock).")
        reference = run_backend(REF_BASE, REF_KEY)
        reset_sim()
        sim_result = run_backend(SIM_BASE, SIM_KEY)
        sys.exit(1 if report(reference, sim_result) else 0)

    sys.exit(f"Unknown mode: {mode}. Use capture | verify | live.")


if __name__ == "__main__":
    main()
