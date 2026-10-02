#!/usr/bin/env python3
"""Keep the simulator's pinned Stripe contract honest.

The simulator models Stripe API version 2024-06-20. contracts/ holds the subset of Stripe's own
OpenAPI document (github.com/stripe/openapi) for the operations the simulator serves, pinned to one
tag with SHA-256 digests in contracts/manifest.json.

  python3 tools/check_upstream.py            # weekly drift check (network: GitHub only)
  python3 tools/check_upstream.py --local    # verify contracts/ against the manifest (no network)
  python3 tools/check_upstream.py --pin TAG  # re-pin the modelled contract to a stripe/openapi tag
  python3 tools/check_upstream.py --ack TAG  # record TAG as the latest upstream a human has reviewed

The drift check (1) re-downloads the pinned upstream document and confirms its SHA-256 is unchanged,
and (2) reads the latest stripe/openapi release and compares the modelled operations with the last
upstream state a human reviewed (`reviewed` in the manifest; initially the pinned tag), listing
operations added, removed or changed since, and whether Stripe's current API version differs from
the modelled one. It exits 1 on drift, 2 on network trouble, and never rewrites the baseline: a
human reviews the change, updates the simulator (and re-pins with --pin) or records the review with
--ack. It only talks to GitHub, never to Stripe's API.
"""
import argparse
import hashlib
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONTRACTS = ROOT / "contracts"
MANIFEST = CONTRACTS / "manifest.json"
SUBSET = CONTRACTS / "stripe-openapi-subset.json"
OUT = ROOT / "build" / "upstream"
REPO = "stripe/openapi"
SPEC_PATH = "openapi/spec3.json"
RAW = "https://raw.githubusercontent.com/{repo}/{ref}/{path}"
METHODS = ("get", "post", "delete")

# The operations the simulator serves (src/main/kotlin/com/fakestripe/control/Operations.kt), as
# Stripe's OpenAPI document names them.
MODELLED = [
    ("post", "/v1/customers"), ("get", "/v1/customers"), ("get", "/v1/customers/{customer}"),
    ("post", "/v1/customers/{customer}"), ("delete", "/v1/customers/{customer}"),
    ("get", "/v1/customers/{customer}/payment_methods"),
    ("post", "/v1/payment_methods"), ("get", "/v1/payment_methods"),
    ("get", "/v1/payment_methods/{payment_method}"), ("post", "/v1/payment_methods/{payment_method}"),
    ("post", "/v1/payment_methods/{payment_method}/attach"), ("post", "/v1/payment_methods/{payment_method}/detach"),
    ("post", "/v1/payment_intents"), ("get", "/v1/payment_intents"), ("get", "/v1/payment_intents/{intent}"),
    ("post", "/v1/payment_intents/{intent}"), ("post", "/v1/payment_intents/{intent}/confirm"),
    ("post", "/v1/payment_intents/{intent}/capture"), ("post", "/v1/payment_intents/{intent}/cancel"),
    ("get", "/v1/charges"), ("get", "/v1/charges/{charge}"), ("post", "/v1/charges/{charge}"),
    ("post", "/v1/charges/{charge}/refunds"),
    ("post", "/v1/refunds"), ("get", "/v1/refunds"), ("get", "/v1/refunds/{refund}"), ("post", "/v1/refunds/{refund}"),
    ("post", "/v1/products"), ("get", "/v1/products"), ("get", "/v1/products/{id}"), ("post", "/v1/products/{id}"),
    ("delete", "/v1/products/{id}"),
    ("post", "/v1/prices"), ("get", "/v1/prices"), ("get", "/v1/prices/{price}"), ("post", "/v1/prices/{price}"),
    ("post", "/v1/subscriptions"), ("get", "/v1/subscriptions"),
    ("get", "/v1/subscriptions/{subscription_exposed_id}"), ("post", "/v1/subscriptions/{subscription_exposed_id}"),
    ("delete", "/v1/subscriptions/{subscription_exposed_id}"),
    ("get", "/v1/subscription_items"), ("get", "/v1/subscription_items/{item}"), ("post", "/v1/subscription_items/{item}"),
    ("get", "/v1/invoices"), ("get", "/v1/invoices/{invoice}"), ("post", "/v1/invoices/{invoice}"),
    ("post", "/v1/invoices/{invoice}/pay"), ("post", "/v1/invoices/{invoice}/void"),
    ("post", "/v1/checkout/sessions"), ("get", "/v1/checkout/sessions"), ("get", "/v1/checkout/sessions/{session}"),
    ("post", "/v1/checkout/sessions/{session}/expire"),
    ("post", "/v1/billing_portal/sessions"),
    ("get", "/v1/events"), ("get", "/v1/events/{id}"),
]


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "xstripe-payment-simulator upstream check"})
    with urllib.request.urlopen(req, timeout=120) as resp:  # noqa: S310 - fixed GitHub URLs
        return resp.read()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def subset(spec: dict) -> dict:
    """The modelled operations plus every schema they reference, transitively."""
    paths = {}
    missing = []
    for method, path in MODELLED:
        op = spec["paths"].get(path, {}).get(method)
        if op is None:
            missing.append(f"{method.upper()} {path}")
            continue
        paths.setdefault(path, {})[method] = op
    if missing:
        raise SystemExit("Upstream no longer has: " + ", ".join(missing))
    schemas = spec["components"]["schemas"]
    wanted, queue = set(), [paths]
    while queue:
        node = queue.pop()
        text = json.dumps(node)
        for name in re.findall(r'"#/components/schemas/([^"]+)"', text):
            if name not in wanted:
                wanted.add(name)
                queue.append(schemas[name])
    return {
        "openapi": spec["openapi"],
        "info": spec["info"],
        "servers": spec.get("servers", []),
        "security": spec.get("security", []),
        "paths": {p: paths[p] for p in sorted(paths)},
        "components": {
            "schemas": {n: schemas[n] for n in sorted(wanted)},
            "securitySchemes": spec["components"].get("securitySchemes", {}),
        },
    }


def encode(doc: dict) -> bytes:
    return (json.dumps(doc, indent=1, sort_keys=True) + "\n").encode()


def operation_digests(doc: dict) -> dict:
    return {
        f"{m.upper()} {p}": sha256(json.dumps(op, sort_keys=True).encode())
        for p, ops in doc["paths"].items() for m, op in ops.items() if m in METHODS
    }


def pin(tag: str) -> int:
    url = RAW.format(repo=REPO, ref=tag, path=SPEC_PATH)
    raw = fetch(url)
    spec = json.loads(raw)
    doc = subset(spec)
    data = encode(doc)
    CONTRACTS.mkdir(exist_ok=True)
    SUBSET.write_bytes(data)
    manifest = {
        "provider": "Stripe",
        "api_version": spec["info"]["version"],
        "source": {
            "repository": f"https://github.com/{REPO}",
            "tag": tag,
            "url": url,
            "sha256": sha256(raw),
            "bytes": len(raw),
        },
        "subset": {
            "file": SUBSET.relative_to(ROOT).as_posix(),
            "sha256": sha256(data),
            "operations": len(operation_digests(doc)),
            "schemas": len(doc["components"]["schemas"]),
        },
        "operations": operation_digests(doc),
        "reviewed": {"tag": tag, "api_version": spec["info"]["version"], "operations": operation_digests(doc)},
    }
    MANIFEST.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"Pinned {tag} (API {manifest['api_version']}): {manifest['subset']['operations']} operations, "
          f"{manifest['subset']['schemas']} schemas -> {SUBSET.relative_to(ROOT)}")
    return 0


def verify_local() -> int:
    manifest = json.loads(MANIFEST.read_text())
    data = SUBSET.read_bytes()
    if sha256(data) != manifest["subset"]["sha256"]:
        print(f"{SUBSET.relative_to(ROOT)} does not match contracts/manifest.json", file=sys.stderr)
        return 1
    doc = json.loads(data)
    if operation_digests(doc) != manifest["operations"]:
        print("Operation digests in the manifest do not match the subset", file=sys.stderr)
        return 1
    if doc["info"]["version"] != manifest["api_version"]:
        print("The subset's info.version does not match the manifest", file=sys.stderr)
        return 1
    print(f"contracts/ is intact: Stripe API {manifest['api_version']}, {len(manifest['operations'])} operations")
    return 0


def check() -> int:
    if verify_local() != 0:
        return 1
    manifest = json.loads(MANIFEST.read_text())
    try:
        pinned_raw = fetch(manifest["source"]["url"])
        latest = json.loads(fetch(f"https://api.github.com/repos/{REPO}/releases/latest"))
        latest_tag = latest["tag_name"]
        latest_raw = fetch(RAW.format(repo=REPO, ref=latest_tag, path=SPEC_PATH))
    except Exception as e:  # network trouble is not drift
        print(f"could not reach GitHub: {e}", file=sys.stderr)
        return 2
    drift = False
    if sha256(pinned_raw) != manifest["source"]["sha256"]:
        print(f"The pinned tag {manifest['source']['tag']} no longer has the recorded content.")
        drift = True
    latest_spec = json.loads(latest_raw)
    latest_version = latest_spec["info"]["version"]
    print(f"Pinned: {manifest['source']['tag']} (API {manifest['api_version']}); latest: {latest_tag} (API {latest_version})")
    if latest_version != manifest["api_version"]:
        drift = True
        print(f"Stripe publishes API {latest_version}; the simulator models {manifest['api_version']}.")
    if (manifest.get("reviewed") or {}).get("tag") == latest_tag:
        print("The latest upstream tag was already reviewed.")
    try:
        current = operation_digests(subset(latest_spec))
    except SystemExit as gone:
        print(gone)
        current = {}
        drift = True
    reviewed = manifest.get("reviewed", {"tag": manifest["source"]["tag"], "operations": manifest["operations"]})
    reviewed_ops = reviewed["operations"]
    print(f"Last reviewed upstream: {reviewed['tag']}")
    added = sorted(set(current) - set(reviewed_ops))
    removed = sorted(set(reviewed_ops) - set(current))
    changed = sorted(op for op in set(current) & set(reviewed_ops) if current[op] != reviewed_ops[op])
    for op in added:
        print(f"  + {op}")
    for op in removed:
        print(f"  - {op}")
    for op in changed:
        print(f"  ~ {op}")
    if added or removed or changed:
        drift = True
    elif latest_version != manifest["api_version"] and reviewed.get("api_version") == latest_version:
        # A newer API version that was already reviewed (and deliberately not modelled) is not drift.
        drift = sha256(pinned_raw) != manifest["source"]["sha256"]
    if drift:
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "stripe-openapi-subset.latest.json").write_bytes(encode(subset(latest_spec)) if current else latest_raw)
        print(f"Upstream drift. The latest subset is in {OUT.relative_to(ROOT)}; review it, update the simulator "
              f"(re-pin with --pin <tag>) or record the review with --ack {latest_tag}.")
        return 1
    print("No drift.")
    return 0


def ack(tag: str) -> int:
    manifest = json.loads(MANIFEST.read_text())
    spec = json.loads(fetch(RAW.format(repo=REPO, ref=tag, path=SPEC_PATH)))
    manifest["reviewed"] = {"tag": tag, "api_version": spec["info"]["version"], "operations": operation_digests(subset(spec))}
    MANIFEST.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"Recorded {tag} (API {spec['info']['version']}) as reviewed.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--local", action="store_true", help="verify contracts/ against the manifest only")
    parser.add_argument("--pin", metavar="TAG", help="re-pin contracts/ to a stripe/openapi tag")
    parser.add_argument("--ack", metavar="TAG", help="record a stripe/openapi tag as reviewed")
    args = parser.parse_args()
    if args.pin:
        return pin(args.pin)
    if args.ack:
        return ack(args.ack)
    if args.local:
        return verify_local()
    return check()


if __name__ == "__main__":
    sys.exit(main())
