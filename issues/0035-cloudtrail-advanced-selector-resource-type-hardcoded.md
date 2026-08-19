# CloudTrail advanced selector `resources.type` hardcoded, lets bucket-level ops evade object-scoped exclusions

**Severity:** 3 (narrow correctness gap, surfaced only when an `AdvancedEventSelectors` config is used to exclude a specific bucket)
**Found:** 2026-08-19, during live verification of the issue 0034 fix on the `1160-ct` parent

## Symptom

With the real-world remediation for AWS issue #1192 applied — an `AdvancedEventSelectors` entry with `resources.type Equals AWS::S3::Object` and `resources.ARN NotStartsWith arn:aws:s3:::<own-log-bucket>/` — the trail's self-referential `PutObject` loop stopped correctly. But a bucket-level, read-only `ListObjects` call against that same excluded bucket (issued from an unrelated `aws s3api list-objects-v2` diagnostic command) still got captured as a data event and delivered, even though the selector was meant to exclude everything under that bucket.

## Root cause

`CloudTrailService.matchesAdvancedFieldSelector` (`src/main/java/io/github/hectorvent/floci/services/cloudtrail/CloudTrailService.java`) hardcoded the `resources.type` field's evaluated value to the literal `"AWS::S3::Object"` for every S3 event, regardless of whether the event actually had an object key. Bucket-level operations (`ListObjects`, etc. — see `S3Controller.listObjects` at line 598, which calls `emitCloudTrailEvent("ListObjects", bucket, null, ...)` with a `null` key) built a bare bucket ARN with no trailing `/` (`arn:aws:s3:::<bucket>`), which never starts with an object-scoped exclusion prefix like `arn:aws:s3:::<bucket>/`. Combined with the hardcoded `resources.type`, an `AWS::S3::Object`-only selector incorrectly matched these bucket-level calls instead of correctly rejecting them (real AWS CloudTrail never matches bucket-level API calls against an `AWS::S3::Object` DataResource selector).

## Impact

Any `AdvancedEventSelectors` config written to exclude a specific bucket from S3 object data-event capture (the standard real-world fix for circular CloudTrail logging) leaves a gap: bucket-level API calls against the excluded bucket are still captured. In this session it showed up as the CloudTrail-loop fix looking incomplete — a `ListObjects` call made the trail appear to still be firing after the fix, even though the actual self-write loop had genuinely stopped.

## Status: Fixed

`matchesAnyAdvancedSelector` now derives the real resource type per event — `AWS::S3::Object` when the S3 event carries an object key, `AWS::S3::Bucket` when it doesn't — and threads it through `matchesAdvancedSelector`/`matchesAdvancedFieldSelector` instead of hardcoding `AWS::S3::Object`. An `AWS::S3::Object` DataResource selector now correctly never matches bucket-level operations. Regression test: `CloudTrailAdvancedSelectorMatchingTest.resourcesTypeEquals_rejectsBucketLevelOperation`. All 8 tests in the suite pass.

## Live verification (both 0034 and 0035, `1160-ct` parent)

1. Deployed blanket `EventSelectors` (all S3 buckets, no exclusions) on `AWSAccelerator-Organizations-CloudTrail`. Seeded one write. Confirmed a self-sustaining loop: every 60s flush cycle drained exactly one pending record and generated exactly one new one — steady ~722-727 byte deliveries, unbounded over 36+ cycles / 35+ minutes with no natural termination.
2. Confirmed compounding: burst-wrote 400 then 600 objects to unrelated buckets while the loop ran. Every external write landed losslessly inside whichever flush cycle it fell into, alongside the self-referential record (verified by exact record-count arithmetic on both bursts).
3. Applied the real-world fix: `AdvancedEventSelectors` with `resources.ARN NotStartsWith arn:aws:s3:::<log-bucket>/`. The self-write loop stopped — last self-referential delivery `02:17:32Z`, zero further organic deliveries.
4. The only delivery after that point (`02:22:32Z`) was traced to a `ListObjects` diagnostic call issued during this investigation, not the loop — which is exactly the 0035 gap above. Fixed and regression-tested as described.
