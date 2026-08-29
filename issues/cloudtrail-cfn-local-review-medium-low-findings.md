# CloudTrail CFN/advanced-selectors branch: deferred local-review findings

**Severity:** 3 (real gaps, none ship-blocking for this PR's scope)
**Found:** 2026-08-29, local code review (`review-local.ts`) pass on
`cloudtrail-cfn-rederive` before opening the PR that re-derives the
~7 genuinely unique commits from the old `feature/cloudtrail-cfn-and-advanced-selectors`
branch.

Two HIGH findings on the new `CloudTrailCfnProvisioner.java` (physical-id-not-set-before-failure,
and update not calling `stopLogging` on explicit `IsLogging: false`) were fixed RED-first in the
same pass — see the `fix(cloudformation): close two audit-control gaps in CloudTrailCfnProvisioner`
commit. Everything below was left for later: either MEDIUM/LOW in a file this PR touches, or a
finding on code this PR did not modify (pre-existing behavior, out of scope for a re-derive).

## In this PR's new code (in scope, deferred as non-blocking)

`CloudTrailCfnProvisioner.java`:
- TrailName change on update is silently ignored instead of triggering CFN replacement
  (TrailName is create-only in real CloudTrail).
- Absent optional properties (S3KeyPrefix, SnsTopicName) on update are passed as null and
  never clear a previously-set value — service layer treats null as "no change."
- `delete()`'s describe-then-delete existence check is TOCTOU and fails open (skips delete) if
  `describeTrails` legitimately returns empty for a still-existing trail.
- `SnsTopicArn` attribute is set even when null; depends on the attributes map implementation
  whether that NPEs or just pollutes `Fn::GetAtt`.
- Update with both selector fields absent leaves previously-applied selectors in place instead
  of resetting to the trail's default (removing selectors from a template doesn't clear them).

`CloudTrailJsonHandler.java` (new actions only — ListTrails/AddTags/ListTags/selector branching):
- `LookupEvents` always returns an empty result set regardless of filters (pre-existing action,
  not modified by this branch, but worth noting since it's easy to conflate with the new actions).
- `AddTags` accepts null/duplicate Keys and silently last-write-wins instead of rejecting with
  `InvalidTagParameterException`.
- `ListTags` does not enforce AWS's 20-entry `ResourceIdList` cap (the sibling `AddTags` got a
  200-item cap fixed in this same pass; `ListTags` was missed).
- `PutEventSelectors` with both `EventSelectors` and `AdvancedEventSelectors` absent/empty stores
  an empty selector list instead of throwing `InvalidEventSelectorsException`.

`CloudTrailSelectorJson.java` (new file):
- Malformed selector input (non-array `EventSelectors`/`AdvancedEventSelectors`/`FieldSelectors`
  nodes, or a non-array `Equals`/`NotEquals`/etc. field) is silently coerced to an empty list
  instead of raising a validation error — a typo'd selector block provisions a trail with no
  data-event coverage and no error.
- Explicit JSON `null` for `IncludeManagementEvents` becomes `false`, and for `ReadWriteType`
  becomes the literal string `"null"`, instead of falling back to the field's real default.

`CloudTrailService.java` (new `addTags`/`listTags`/`putAdvancedEventSelectors`/etc. methods):
- Same non-atomic read-modify-write pattern as the rest of the service (see below) — new methods
  inherit the existing `store.get(key).ifPresent(entry -> store.put(...))` idiom, so they share
  the same lost-update race under concurrent mutation. Fixing this needs a StorageBackend-level
  compare-and-swap primitive, which is bigger than this PR's scope.

## Pre-existing code this PR did not modify (inherited-not-ours)

`CloudTrailJsonHandler.java`: `putEventSelectors`/`getEventSelectors` NPE via `List.of(trailName)`
when `TrailName` is absent (500 instead of a structured 400) — this line predates this branch.

`CloudTrailLogWriter.java` (this PR only added the `emitS3DataEvent` call at the tail of
`flushTrail`; everything else below predates it):
- `stop()`'s shutdown flush races the scheduled flush, then `shutdownNow()` can interrupt a
  flush mid-write and drop the drained batch.
- `flushTrail`/`flushAll` only catch `RuntimeException`; an escaping `Throwable` silently cancels
  the periodic scheduled task, permanently stopping all future CloudTrail delivery.
- `flushNow()` has no mutual exclusion with the scheduled flush — concurrent partial-batch drains
  can reorder or duplicate delivered records.
- `s3KeyPrefix` is concatenated into the object key with no path-traversal sanitization.
- Failed-flush requeue appends to the tail instead of the head, reordering the audit stream
  relative to newer records.

`CloudTrailService.java` (existing methods, pre-existing before this branch):
- `createTrail`'s check-then-put is a TOCTOU race that can silently overwrite an existing trail
  instead of throwing `TrailAlreadyExistsException`.
- General lost-update race across `updateTrail`/`putEventSelectors`/`startLogging`/`stopLogging`
  (see the shared idiom note above — same root cause, needs a backend-level fix).
- `deleteTrail` purges pending records by trail name only, discarding undelivered records for
  same-named trails in other regions.
- `buildS3Record` writes the raw (possibly null) `in.region()` into `awsRegion` instead of the
  resolved region used for trail matching.
- Null/`"test"` access keys are attributed to the account-root identity instead of anonymous.
- `isReadOnlyEvent(null)` defaults to read-only; the safer default for an unclassifiable event
  is write (captured by the stricter selector).
- `updateTrail` accepts an explicit empty `s3BucketName` that `createTrail` would reject.

## Suggested fix direction

Most of the "in scope" items are single-method validation additions (reject malformed input,
throw the modeled AWS exception) and can land as small follow-up fixes. The concurrency items
(non-atomic read-modify-write, `flushNow`/scheduled-flush races) need an actual design decision
(per-key locking vs. a CAS-capable `StorageBackend` method) and should be scoped as their own PR
rather than folded into further CloudTrail feature work.
