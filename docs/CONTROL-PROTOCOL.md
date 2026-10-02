# Siere simulator control protocol, version 1

Every provider simulator used by SSF (`xstripe-payment-simulator`, `xsumup-payments-simulator`, `xwolt-api-simulator`,
`xglovo-simulator`, `xfiscal-device-simulator`) implements this protocol on its private controller, so a scenario runner
(`ssf-platform :tools:scenarios`) drives all of them the same way: one clock, named faults, one event journal, snapshots.

This document is canonical. Each simulator keeps a verbatim copy in `docs/CONTROL-PROTOCOL.md` and a
`ControlProtocolTest` that exercises every requirement below against its real listeners. A change to this document bumps
the protocol version and is applied to every simulator.

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 1. Listeners and authentication

- A simulator has an **actor** listener (the provider's API or device protocol) and a **controller** listener (this
  protocol). Both bind `127.0.0.1` by default; the controller MUST NOT share a port with the actor.
- The controller authenticates every route under `/v1/admin/` with the header `X-Siere-Control-Token`, compared in
  constant time. The token comes from the simulator's own environment variable (`FAKE_STRIPE_CONTROL_TOKEN`,
  `FAKE_SUMUP_CONTROL_TOKEN`, `WOLT_SIM_CONTROL_TOKEN`, `GLOVO_SIM_CONTROL_TOKEN`, `FISCAL_SIM_CONTROL_TOKEN`) and MUST be
  at least 16 characters.
- Missing or wrong token: `403` with code `forbidden`. No token configured: the simulator MUST NOT serve the controller
  (it either refuses to start or does not open the controller port), and says so in its log.
- The actor listener MUST answer `404` for any path under `/v1/admin/`.
- Every controller response carries `X-Siere-Control-Protocol: 1`.

## 2. Conventions

- JSON bodies, UTF-8, `snake_case` field names. Unknown request fields are ignored.
- Times are ISO-8601 UTC with milliseconds: `2026-10-02T10:00:00.000Z`.
- Errors: `{"error": {"code": "<code>", "message": "<human text>"}}` with codes `forbidden` (403), `not_found` (404),
  `invalid_request` (400), `conflict` (409), plus the specific codes named below. Messages never echo secrets.
- Every mutating controller call is atomic: it either fully applies (and is persisted before the reply) or changes
  nothing.

## 3. Health and lifecycle

| Route | Purpose |
|---|---|
| `GET /healthz` (both listeners, no authentication) | `200 {"status": "ok"}` once the simulator serves requests |
| `GET /v1/admin/health` | identity and position, see below |
| `POST /v1/admin/reset` | a fresh deterministic world |
| `GET /v1/admin/state` | the simulator's whole world, secrets redacted (simulator-specific shape, MUST include `revision`; field names MAY keep a simulator's established casing where clients already read them) |
| `GET`, `PATCH /v1/admin/config` | simulator-specific switches; every simulator with callbacks supports `webhook_delivery` |

`GET /v1/admin/health` answers:

```json
{
  "status": "ok",
  "simulator": "glovo",
  "version": "0.2.0",
  "protocol": 1,
  "seed": "lunch-rush-1",
  "episode_id": "run-0007",
  "clock": {"now": "2026-10-05T08:00:00.000Z", "mode": "manual"},
  "revision": 42,
  "journal_seq": 311
}
```

`POST /v1/admin/reset` takes `{"seed": "<text>", "episode_id": "<text>", "scenario": "<name>", "clock_mode":
"manual|free", "now": "<time>"}`; every field is optional (seed defaults to the configured one, mode to `manual`, `now`
to the simulator's documented start). A reset replaces the world, clears faults and pending callbacks, empties the
journal (sequence numbers keep increasing, see 6) and then journals the reset itself as one `control` entry, keeps named
snapshots, and answers like `GET /v1/admin/health`. Simulators that accepted reset parameters in the query string keep
accepting them.

**Revision.** `revision` increases with every change of the world, including resets and restores, and never goes back:
a client that saw revision 42 knows any later answer with 42 is the same world.

**Determinism.** Same seed, same scenario, same sequence of actor requests and clock operations MUST produce the same
identifiers, the same response bodies and the same callbacks. Identifiers come from a seeded generator whose position is
part of the world.

## 4. Clock

The simulated clock is the simulator's only notion of business time (token expiry, quote validity, courier timetables,
retry schedules, printer date). Only HTTP socket timeouts and the `latency` fault (section 5) use real time.

| Route | Purpose |
|---|---|
| `GET /v1/admin/clock` | `{"now": "<time>", "mode": "manual|free"}` |
| `POST /v1/admin/clock` `{"now": "<time>"}` | move to an absolute time |
| `POST /v1/admin/clock/advance` `{"seconds": <number>}` | move forward by `0 < seconds <= 31536000` |

- In `manual` mode the clock stands still between calls. In `free` mode it follows the wall clock and both moving routes
  answer `409 clock_not_manual`.
- Moving to a time before `now` answers `409 clock_backwards`. Moving to `now` is allowed and settles due work.
- Moving the clock settles every event that falls due on the way **in time order**, as if the clock had moved in many
  small steps: one long move and many short ones MUST end in the same world.
- Both moving routes answer `{"now": "<time>", "mode": "manual", "settled": <events applied>}`.
- A scenario keeps several simulators in step by setting each to the same absolute time.

## 5. Faults

Faults are named rules the controller installs and the actor consumes.

| Route | Purpose |
|---|---|
| `GET /v1/admin/faults` | `{"faults": [<fault>]}` in installation order |
| `POST /v1/admin/faults` | install one fault; `201` with the fault; `409 fault_exists` when the name is taken |
| `DELETE /v1/admin/faults/{name}` | remove one; `204`, `404` when unknown |
| `DELETE /v1/admin/faults` | remove all; `204` |

A fault:

```json
{
  "name": "refund-reply-lost",
  "match": {"operation": "refund.create"},
  "kind": "commit_then_drop",
  "status": 503,
  "body": {"error": {"message": "injected"}},
  "headers": {"Retry-After": "2"},
  "delay_ms": 0,
  "skip": 0,
  "count": 1,
  "remaining": 1,
  "injected": 0
}
```

- `name`: `[A-Za-z0-9._-]{1,64}`, chosen by the caller.
- `match`: exactly one of `{"operation": "<id>"}` (the simulator's documented operation identifiers) or
  `{"method": "<HTTP method>", "path": "<pattern>"}` where the pattern matches the request path segment by segment, `*`
  matching one segment and a trailing `**` any remainder. A simulator MAY define further match keys (the fiscal
  simulator matches `{"command": <opcode>}`) and documents them.
- `kind`:
  - `error`: answer `status`, `headers`, `body` without executing the request.
  - `commit_then_error`: execute and commit, then answer `status`, `headers`, `body` instead of the real response. A
    retry with the same idempotency key, where the provider has them, finds the committed result.
  - `commit_then_drop`: execute and commit, then close the connection without writing any response bytes. The client
    sees the connection end; nothing tells it whether the request took effect.
  - `drop_before`: close the connection without executing.
  - `latency`: wait `delay_ms` (`0..60000`, real time) and then handle the request normally.
  - Simulator-specific kinds start with `x_` (for example `x_outcome` for Glovo's menu jobs or `x_nak` for the fiscal
    link) and are documented by that simulator.
- `skip` (default `0`) matching requests pass untouched before the fault starts; it then applies to the next `count`
  (`1..1000`, default `1`) matching requests. `remaining` and `injected` are reported. A spent fault stays listed with
  `remaining: 0` until deleted or reset.
- Every fault that matches a request counts it toward its own `skip`. Of the faults that are past their `skip` and have
  `remaining > 0`, the first installed one applies; the others don't consume the request.
- `status` is `400..599`. A kind that cannot apply to a match (a transport the simulator doesn't speak that way) is
  refused with `400 invalid_request` when installed.
- Faults apply to actor requests whether or not the request would pass the provider's own authentication. `/healthz` is
  never faulted or journaled.
- Faults are part of the world: persisted, cleared by reset, carried by snapshots. Each application is journaled (6).

## 6. Journal

The journal records what crossed the simulator's boundaries, so a scenario can merge it into one timeline.

`GET /v1/admin/journal?after=<seq>&limit=<1..1000, default 200>&kind=<kind>&bodies=<true|false>` answers

```json
{"entries": [<entry>], "next": 311, "oldest": 120}
```

`after` is exclusive. `next` is the value to pass as `after` for the following page: the `seq` of the last entry returned,
or the request's own `after` when nothing was returned. A client pages until a page comes back empty.

An entry:

```json
{
  "seq": 311,
  "episode_id": "run-0007",
  "at": "2026-10-05T08:03:00.000Z",
  "wall": "2026-10-02T09:14:07.381Z",
  "kind": "request",
  "operation": "refund.create",
  "method": "POST",
  "path": "/v1/refunds",
  "status": 200,
  "outcome": "dropped",
  "request_id": "req_4Fz9bQ2LmT0aY1",
  "correlation_id": "step-14",
  "idempotency_key": "refund-sale-7",
  "refs": {"payment_intent": "pi_...", "refund": "re_..."},
  "fault": "refund-reply-lost",
  "detail": null
}
```

- `kind` is one of `request` (an actor request, written once its outcome is known), `callback` (one entry per webhook or
  callback attempt: `path` is the target's path, `status` its answer or `null`, `outcome` `delivered`, `failed` or
  `exhausted`, `refs.event` the event id, `refs.attempt` the attempt number), `state` (a simulator-driven change such as
  a courier moving or a job finishing), `fault` (a fault applied), `clock` (a clock move) and `control` (a reset,
  restore or configuration change).
- `outcome` for requests: `answered`, `error_injected`, `dropped` (no response was written) or `rejected` (refused before
  execution).
- `seq` increases by one per entry for the life of the process and never restarts, including across resets and
  restores. `oldest` is the first sequence still held.
- The journal keeps at least the last 10 000 entries, is part of the persisted world, and is emptied (not renumbered)
  by reset.
- Bodies are held up to 16 KiB each, with every secret, token, card number and signature replaced by `"[redacted]"`;
  they appear only with `bodies=true` as `request_body` and `response_body`.
- `request_id` is deterministic (seeded) and is returned to the actor's caller in the provider's own request-id header
  where the provider has one, otherwise in `X-Siere-Request-Id`. A caller MAY send `X-Siere-Correlation-Id`; it is
  recorded as `correlation_id`, cut to 128 characters.

## 7. Callbacks

Simulators that call out (webhooks, notifications, status callbacks) keep a persisted outbox and follow the provider's
documented retry schedule on the simulated clock.

| Route | Purpose |
|---|---|
| `GET /v1/admin/webhooks?status=pending|delivered|failed|exhausted` | the outbox: `{"webhooks": [<job>]}` |
| `POST /v1/admin/webhooks/flush` `{"ids": [..], "reverse": false, "concurrency": 1, "include_future": false}` | deliver due jobs now and answer `{"results": [<job>]}`. Listed `ids` are delivered even when not yet due; an unknown id answers `404` before anything is sent. `reverse` delivers newest first; `concurrency` `1..16` delivers in parallel; `include_future` also delivers jobs not yet due |
| `POST /v1/admin/webhooks/{id}/replay` | enqueue an exact duplicate (same event id, same signed bytes); answers the new job |

A job: `{"id", "event_id", "event_type", "target" (the callback's path), "status", "attempts", "next_attempt_at",
"last_status"}`. Status `pending`: not attempted yet; `failed`: the last attempt failed and another is scheduled;
`delivered`; `exhausted`: no attempts left.

`PATCH /v1/admin/config {"webhook_delivery": "auto|manual"}`: `auto` delivers due jobs in the background as the provider
would; `manual` delivers only on flush. Callback targets keep the simulator's existing allow-list rules.

## 8. Snapshots

| Route | Purpose |
|---|---|
| `POST /v1/admin/snapshots` `{"name": "<name>", "replace": false}` | save the current world; `201` with the metadata; `409 snapshot_exists` unless `replace` |
| `GET /v1/admin/snapshots` | `{"snapshots": [<metadata>]}` |
| `GET /v1/admin/snapshots/{name}` | one snapshot's metadata |
| `DELETE /v1/admin/snapshots/{name}` | `204` |
| `POST /v1/admin/snapshots/{name}/restore` | replace the world with the snapshot; answers like health |

- Metadata: `{"name", "created_at" (wall time), "clock" (simulated now), "revision", "episode_id", "format", "bytes"}`.
- A snapshot holds the whole world: entities, clock, identifier position, faults, outbox and configuration. It does not
  hold the journal; a restore appends a `control` journal entry instead.
- Snapshots are files beside the simulator's state (`<data dir>/snapshots/<name>.json`), written atomically, and survive
  restarts and resets. Names follow the fault name rule.
- A restore ends anything waiting on the old world (long polls answer as after a reset) and is persisted before the
  reply. A snapshot in an incompatible format answers `409 snapshot_incompatible` and changes nothing.

## 9. Conformance

Each simulator's `ControlProtocolTest` starts the real listeners on free ports and checks, at least: authentication and
the actor's `404`; health and `healthz`; reset determinism (two resets with one seed give identical worlds); absolute and
relative clock moves with time-ordered settling and the `409` cases; every fault kind including a real dropped
connection, `skip`, `count`, ordering and persistence across restart; journal paging, sequence continuity across reset,
redaction and correlation; flush, reverse, replay and both delivery modes where the simulator has callbacks; snapshot
save, list, restore after further changes, restart survival and the incompatible case.

The scenario runner refuses a simulator whose health reports another `protocol`.
