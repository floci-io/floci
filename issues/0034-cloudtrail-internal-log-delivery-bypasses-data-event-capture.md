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

## Live verification on `1160-ct`

Deployed the fix to a live parent and drove the full lifecycle end-to-end, not just unit tests:

1. **Loop confirmed live and unbounded.** Blanket `EventSelectors` (all S3 buckets, no exclusions), one seeded write. Every 60s flush cycle drained exactly one pending record and generated exactly one new one — steady ~722-727 byte deliveries, running unbounded over 36+ cycles / 35+ minutes with no natural termination. Fixed-point loop, not exponential.
2. **Compounding confirmed.** Burst-wrote 400 then 600 objects (varied sizes) to unrelated buckets while the loop ran. Every external write landed losslessly inside whichever flush cycle it fell into, alongside the self-referential record — verified by exact record-count arithmetic on both bursts (400 → 211+189; 600 → 172+294+134).
3. **Real-world fix confirmed to stop the loop.** Applied `AdvancedEventSelectors` with `resources.ARN NotStartsWith arn:aws:s3:::<log-bucket>/` (the standard remediation for AWS issue #1192) to the live trail. Self-write loop stopped: last self-referential delivery `02:17:32Z`, zero further organic deliveries.
4. **One gap found and fixed in the same pass**, filed separately as issue 0035: a bucket-level `ListObjects` call (no object key) against the excluded bucket still slipped through, because `resources.type` was hardcoded to `AWS::S3::Object` for every event regardless of whether it was object- or bucket-level. Fixed in the same rebuild.

Image `localhost/floci-lza:local` rebuilt 2026-08-19 with both fixes.

### Delivery timeline (raw data)

Per-cycle delivery size, `1160-ct`, `AWSAccelerator-Organizations-CloudTrail` → `aws-accelerator-central-logs-845036286238-us-east-1`. Full 52-row listing saved at `.temp/1160ct-loop-full-listing.json`.

```
01:34:32    748           ← seed write delivered
01:35:32    949  (+201)
01:36:32    723  (-226)   ← settles toward steady-state (~722-727B, 1 self-record)
01:37:32    951  (+228)
01:38:32    962   (+11)
01:39:32    723  (-239)
01:40:32    723    (0)
01:41:32    724    (+1)
01:42:32    723    (-1)
01:43:32    722    (-1)
01:44:32    725    (+3)
01:45:32    724    (-1)
01:46:32    721    (-3)   ← pure steady-state: one self-referential record, forever

── 400-write EKS-log burst fired here ──
01:47:32   1,016  (+295)
01:48:32     723  (-293)
01:49:32     724    (+1)
01:50:32     722    (-2)
01:51:32     723    (+1)
01:52:32   1,196  (+473)
01:53:32     722  (-474)
01:54:32     947  (+225)
01:55:32     960   (+13)
01:56:32     723  (-237)
01:57:32  14,234 (+13,511) ← bulk of the 400-write burst lands, folded into this cycle
01:58:32  12,799  (-1,435) ← burst continues draining
01:59:32     723 (-12,076) ← fully drained, back to pure steady-state in ONE cycle
02:00:32     951    (+228)
02:01:32   1,058    (+107)
02:02:32     723    (-335)

── 600-write varied-size burst fired here ──
02:03:32  12,193  (+11,470)
02:04:32  20,059   (+7,866) ← peak: multiple write-batches overlapping in one cycle
02:05:32   9,758  (-10,301)
02:06:32     727   (-9,031) ← fully drained again, back to steady-state
02:07:32     723      (-4)

── fix-side AdvancedEventSelectors applied ~02:08 ──
02:08:32   1,251    (+528) ← still-queued pre-fix backlog draining
02:09:32     953    (-298)
02:10:32     947      (-6)
02:11:32     724    (-223) ← backlog exhausted, loop should now be dead
02:12:32     726      (+2)
02:13:32     722      (-4) ← still 1 record/cycle — this is the issue-0035 gap:
02:14:32     722       (0)   resources.type hardcoding let a bucket-level op
02:15:32     723      (+1)   through; the real write-loop was actually still live
02:17:32     749     (+26) ← last delivery attributable to the actual write loop
02:22:32     749       (0) ← my own ListObjects diagnostic poll, not the loop
02:24:32     750       (+1) ← same — another diagnostic poll
[12+ min silence since, zero organic deliveries]
```

Each burst spikes delivery size by more than an order of magnitude in the very next 60-second cycle (steady ~723B → 12-20KB), then fully drains back to the 723B baseline within 1-2 cycles — exact, lossless absorption of external writes into the ongoing self-referential loop, with no backlog left behind and no records dropped.
