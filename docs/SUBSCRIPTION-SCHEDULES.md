# Future subscription terms — 0.3.0

The simulator supports the existing-subscription workflow used by SSF billing. It follows Stripe's
[two-step migration](https://docs.stripe.com/api/subscription_schedules/create),
[schedule object](https://docs.stripe.com/api/subscription_schedules/object) and
[release](https://docs.stripe.com/api/subscription_schedules/release) API. The six actor operations
are included in the existing pinned 2024-06-20 OpenAPI subset; Basil subscription/invoice rendering
continues to depend on the request's Stripe-Version.

1. `POST /v1/subscription_schedules` with only `from_subscription=sub_...` and an idempotency key.
   An active or past-due subscription without pending cancellation is linked to one active schedule.
   The current phase retains its items and paid-period boundaries. No invoice is created.
2. `POST /v1/subscription_schedules/{id}` with a new idempotency key. Supply `end_behavior=release`
   or `cancel`, current and future `phases[n]`, and optional schedule metadata. The first submitted
   phase starts at the active phase's original start and keeps its existing items; later phases are
   contiguous. Phases accept recurring `items[n][price|quantity]`, `start_date`, `end_date`,
   `iterations` or `duration[interval|interval_count]`, metadata, `billing_cycle_anchor` and
   `proration_behavior`. Price currencies must match the subscription. Supported phases end on
   complete recurring billing periods. Invalid/unsupported input refuses before changing state.
3. Advance the controller clock to the next phase boundary. The scheduler changes items/cadence and
   patches subscription metadata before the ordinary renewal invoice is created and collected.
   Month = 30 days and year = 365 days, as elsewhere in this simulator. A decline leaves that
   invoice open and the subscription past_due. `POST /v1/invoices/{id}/pay` retries it through the
   payment engine; one InvoicePayment follows the latest attempt. Repeating an already-paid invoice
   does not create another charge.
4. `POST .../{id}/release` detaches the schedule and leaves current recurring terms running.
   Releasing before the future phase therefore preserves the original terms. `preserve_cancel_date`
   is supported. `POST .../{id}/cancel` cancels the underlying subscription immediately. At the final
   phase boundary, end_behavior releases or cancels before any next renewal charge.

Retrieve/list use `GET /v1/subscription_schedules[/{id}]`; customer and common created/pagination
filters follow other lists. Subscriptions expose `schedule`; the common expansion mechanism can
resolve it. Schedules render current_phase, phases, status, end_behavior and release/completion/
cancellation timestamps. Phase metadata uses Stripe's empty-value deletion semantics. Active
schedule-managed subscription items/cancellation cannot be changed directly; release first.

All actor requests use form encoding and existing authentication/idempotency. Native operation IDs
are `subscription_schedule.create|list|retrieve|update|release|cancel`. Native commit_then_error/drop
faults preserve the committed answer for replay. The shared journal records schedule transitions,
and saved worlds retain schedules and phase cursors. Old worlds with no schedule fields still load.

## Supported subset

This release does not create schedules directly from customer/start_date, not_started schedules,
trial phases, phase tax/discount/invoice-item settings or mid-period/active-item edits. Boundary
proration settings are validated but do not produce prorations because active items are unchanged
and every transition is at a full period boundary. Prorated cancellation and cancellation with
pending prorations are refused rather than silently discarding money. Forced controller renewal
of a managed subscription is refused: use the clock so phase chronology is preserved. This is a
local compatibility simulator, not Stripe certification.

## Verification

`SubscriptionScheduleTest` covers monthly-to-yearly transitions, final release/cancel, failed
renewal and retry without duplicate collection, invalid-input atomicity, old/new snapshots, native
HTTP faults/idempotency and unmodified stripe-java schedule deserialization. Existing recurring,
clock/controller and Basil billing checks provide focused regression coverage. The authorized SSF
walkthrough used the real versioned distribution and its retained world, hosted Checkout, browser
subscription/CRM flows, named dropped replies and clock advancement; no ad hoc provider fixture or
manufactured reconciliation record was needed for that walkthrough.
