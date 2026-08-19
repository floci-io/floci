# CloudTrail internal log delivery bypasses data-event capture

**Severity:** 4 (correctness gap in a feature meant to demonstrate a real AWS bug behaviorally)
**Found:** 2026-08-19, during the `uc-build-loop` "before" measurement for PR #1194 (CloudTrail advanced event selectors)

## Symptom

Deployed the pathological config (org trail, `s3DataEvents: true`, no exclusions) against a fresh `1160-ct` parent. Confirmed via `lza aws 1160-ct cloudtrail get-event-selectors` that the blanket S3 data-event selector (`arn:aws:s3:::`) is live and matches the trail's own delivery bucket (`aws-accelerator-central-logs-...`). The trail delivered 6 log files over ~9 minutes. None of them contain a `PutObject` record for the central-logs bucket itself — i.e. floci does not reproduce the circular-logging loop that PR #1194 / issue #1192 describe.

## Root cause

`CloudTrailLogWriter.flushTrail()` (`src/main/java/io/github/hectorvent/floci/services/cloudtrail/CloudTrailLogWriter.java:124`) writes delivered log files via a direct Java call: `s3Service.putObject(trail.s3BucketName(), objectKey, payload, ...)`. This goes straight into the `S3Service` layer.

Data-event emission lives one layer up, in `S3Controller.emitCloudTrailEvent(...)` (`src/main/java/io/github/hectorvent/floci/services/s3/S3Controller.java:124`), which is only reachable from the HTTP-facing controller methods that handle real S3 API calls. Internal service-to-service writes (like CloudTrail's own log delivery) never pass through the controller, so they never call `emitCloudTrailEvent`, so they never generate a data event — even when the active selector would otherwise match.

## Impact

The CloudTrail emulation (merged commit `100f2ac9`) can prove a dangerous config is *live* (selectors, mutual exclusivity, trail-to-bucket wiring all behave correctly) but cannot demonstrate the *consequence* — the circular-logging loop itself — because the emulator's own internal writes are invisible to its own data-event capture. Anyone trying to use floci to reproduce AWS issue #1192 end-to-end will see the trail deliver quietly instead of looping, which could be read as "the bug doesn't exist" rather than "the emulator can't see it."

## Suggested fix direction

Either route `CloudTrailLogWriter`'s delivery write through `S3Controller`/whatever emits data events (so internal writes are treated identically to API-driven ones), or add an explicit `cloudTrailService.recordDataEvent(...)`-style call at the point of delivery so `flushTrail` self-reports like a real S3 PutObject would. Out of scope for the current CloudTrail work — filed for later triage.

## Status: Fixed

`CloudTrailLogWriter.flushTrail()` now calls `cloudTrailService.emitS3DataEvent(...)` immediately after each successful delivery write, using the same evaluation path (`trailsMatching`/selector matching) that API-driven S3 writes go through. Regression test: `CloudTrailSelfDeliveryTest.trailWithBlanketSelectorCapturesItsOwnLogDeliveryAsDataEvent` — creates a trail with a blanket selector pointed at its own destination bucket, forces two flushes, and asserts the second delivery contains a `PutObject` record for the first delivery's own log key. RED confirmed before the fix (only 1 log file delivered instead of 2); GREEN after (48/48 CloudTrail tests passing, no regressions). Fixed commit: `ad229a6b`.
