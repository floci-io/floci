# [epic] systematic account+region-scoping audit across the whole services/ tree

- **Status:** Open — epic/tracking, no sweeps beyond issues/0008 run yet
- **Labels:** epic, correctness, multi-account, multi-region
- **Severity:** n/a (epic — tracks a family of findings, each triaged/severity-rated individually)
- **Opened:** 2026-08-12
- **Found on branch:** `integration/all-features`

## Why this epic exists

The same bug shape has now been found independently three times this session, each time in a different corner of the codebase:

- **issues/0004** — `LaunchedContainerAwsEnv`/`ContainerLauncher`: non-role-assuming launched containers fell back to a placeholder AKID that resolved to whichever account was ambient, not the Lambda's real owner.
- **issues/0007** — `LambdaService.createFunction`: CloudFormation had already resolved the real target account for a Lambda but never threaded it through creation, so the function got stamped with the ambient account instead.
- **issues/0008** — a deliberate 26-file sweep of the CFN provisioning path (provisioners, IAM, EC2, SQS, StackSets, Lambda launch/store) found **13 more instances** of the same shape: code with an explicit, already-resolved account in scope reaching for `RegionResolver.getAccountId()`, ambient `RequestContext`, or an unscoped shared/static store instead.

issues/0008 was deliberately scoped to the CFN provisioning path only (`services/cloudformation/**`, the Lambda launch path, and the account-resolution core) because that's where the two confirmed live-run failures happened. It did **not** cover the other ~70 service modules under `src/main/java/io/github/hectorvent/floci/services/`, nor did it specifically hunt for the **region**-ambient variant of the same bug (resolving `regionResolver.getDefaultRegion()`/ambient region instead of an already-known explicit region) — the sweep's prompt was account-focused per the two confirmed bugs, but the identical structural flaw applies equally to region.

This epic tracks bringing the same systematic, evidence-driven sweep to the rest of the codebase, service domain by service domain, rather than waiting for each one to surface as a live-run failure.

## Scope

**Already covered (issues/0008):** `services/cloudformation/**` (provisioner registry + all 13 concrete provisioners), `services/lambda/launcher/ContainerLauncher.java`, `services/lambda/{LambdaService,LambdaFunctionStore}.java`, `services/iam/IamService.java`, `core/common/{RegionResolver,RequestContext}.java`, `core/common/docker/LaunchedContainerAwsEnv.java`.

**Not yet covered — candidate service domains for future sweeps**, grouped by how likely they are to hit this bug shape (multi-account CFN-provisioned resources first, since that's the pattern with two confirmed hits):

1. **High-likelihood (CFN/CDK-provisioned by LZA, storage-backed, similar shape to issues/0008's finds):** `s3`, `kms`, `route53`, `route53resolver`, `cloudtrail`, `configservice`, `organizations`, `ram` (partially touched already — came back clean in 0008 for `RamResourceShareCfnProvisioner` specifically, but `RamService` itself wasn't in scope), `ssoadmin`, `backup`, `codebuild`, `codepipeline` (their *service* classes, not just the CFN provisioners already covered), `elbv2`, `autoscaling`, `eventbridge`, `sns`, `stepfunctions`.
2. **Medium-likelihood (multi-account-relevant but less central to LZA's own stacks):** `apigateway`, `apigatewayv2`, `appsync`, `cloudfront`, `cognito` (service-side, not just the provisioner call site already found in 0008), `dynamodb`, `ecr`, `ecs`, `eks`, `secretsmanager` (service-side, not just the provisioner call site already found in 0008), `sqs` (service-side, not just the provisioner call site already found in 0008), `ssm`.
3. **Lower-likelihood / not CFN-account-scoped by nature (still worth a pass, but not first):** everything else under `services/` — `acm`, `amazonmq`, `appconfig`, `applicationautoscaling`, `athena`, `batch`, `bcmdataexports`, `bedrockruntime`, `ce`, `cloudcontrol`, `cloudmap`, `cloudwatch`, `codedeploy`, `controltower`, `cur`, `docdb`, `elasticache`, `elasticbeanstalk`, `emr`, `firehose`, `floci`, `glue`, `iot`, `kinesis`, `kinesisanalytics`, `lightsail`, `memorydb`, `msk`, `mwaa`, `neptune`, `opensearch`, `pipes`, `pricing`, `rds`, `rdsdata`, `resourcegroupstagging`, `rum`, `s3vectors`, `scheduler`, `servicequotas`, `ses`, `textract`, `transcribe`, `transfer`, `wafv2`.

**Region-ambient variant:** a dedicated pass (could run alongside any of the above, or as its own sweep) specifically hunting for `regionResolver.getDefaultRegion()`/ambient-region reads where an explicit region was already resolved and in scope — issues/0008's prompt was account-focused; region wasn't systematically checked even within the 26 files it did cover. **Caveat, per user correction (2026-08-12), now sourced against the authoritative AWS whitepaper** ([Fault Isolation Boundaries — Global services](https://docs.aws.amazon.com/whitepapers/latest/aws-fault-isolation-boundaries/global-services.md), fetched 2026-08-12) rather than general knowledge:

- **Confirmed global, safe to exclude from region-ambient findings** (control plane hosted in one Region, but the *resource itself* has no region dimension): **IAM** (control plane `us-east-1`, but each Region's data plane is isolated — resources like roles/users/policies are account-wide, not region-keyed), **Organizations** (`us-east-1`), **Route 53 Public/Private DNS hosted zones** (`us-east-1` control plane — hosted zones themselves have no region), **CloudFront** distributions (`us-east-1` control plane, distributions are global resources), **AWS WAF for CloudFront** — but **only** the `CLOUDFRONT` scope; WAFv2's `REGIONAL` scope (used with ALB/API Gateway/AppSync) is genuinely per-Region and must stay in scope.
- **Corrections to the prior (uncited) list — do NOT exclude these:**
  - **ACM is not global.** Certificates are Regional resources you must request per-Region; the only global-ish rule is that a cert *used with CloudFront* must specifically live in `us-east-1`. floci's `acm` service should stay in the audit scope.
  - **S3 is not global.** Bucket names are globally *unique*, and a handful of config-plane APIs (`PutBucketCors`, `PutBucketPolicy`, `CreateBucket`/`DeleteBucket`, etc.) have an underlying `us-east-1` control-plane dependency — but buckets themselves are Regional resources. floci's `s3` service stays fully in scope; do not treat "no explicit region" as automatically correct there.
  - **STS is not a true global resource**, just a legacy default global endpoint (`us-east-1`) that SDKs/CLIs can and should override with Regional STS endpoints. Not a basis for excluding anything in floci (no dedicated `sts` service module in scope anyway).
  - **Route53Resolver (DNS Firewall, resolver endpoints/rules) is NOT covered by this global-services list** — only Route 53's own Public/Private DNS hosted zones are called out as global. floci's `route53resolver` service must stay in the audit's "High-likelihood" bucket, not be excluded alongside `route53`.
  - **SSO/Identity Center (`ssoadmin`) is not confirmed global by this source at all** — it wasn't in the whitepaper's partitional-services list. Downgrade from "known-global, exclude" to "unconfirmed — verify per-API before excluding it from a region-ambient sweep"; treat it as in-scope until checked.

A region-ambient sweep must exclude/downweight only the confirmed-global bullet above rather than flag its lack of an explicit region as a defect — that would be a false positive, not a landmine. Everything else (including the corrected items) stays in normal scope.

## Static pre-filter (added 2026-08-12) — narrows candidates before spending any LLM tokens

Explored whether this bug shape is statically detectable instead of requiring a full LLM sweep per file. Findings:

- **Tried and rejected**: a single-method ast-grep rule (flag `regionResolver.getAccountId()`/`requestContext.getAccountId()` calls inside a method that *also* has an explicit `accountId`/`ProvisionContext` parameter). This is backwards from the real bug shape — the actual defect spans a caller/callee boundary (the *caller* has the account resolved, the *callee* it invokes doesn't receive it and falls back to ambient state). Verified empirically against `src/main/java`: this rule produced exactly one hit, and it was a correct fallback ternary (`isBlank(accountId) ? regionResolver.getAccountId() : accountId`) — a false positive, not a landmine. ast-grep can't safely bridge a caller/callee gap without full interprocedural data-flow analysis, which is out of reach for a structural pattern tool.
- **`tokensave_callers` undercounts badly for this pattern**: querying callers of `RegionResolver.getAccountId()`/`RequestContext.getAccountId()` via the tokensave MCP call graph returned only 7 direct callers each (mostly test `setUp()` methods), while `ast-grep --lang java -p 'regionResolver.getAccountId()' src/main/java` found **126** real call sites and `requestContext.getAccountId()` found **7 more** (plus 4 via `requestContextInstance.get().getAccountId()`). tokensave's static call graph appears to badly undercount field-typed-receiver call sites for this codebase — worth reporting upstream per the project's tokensave rule (strip proprietary code first) if this recurs elsewhere.
- **What does work as a cheap, high-precision proxy**: cross-reference files containing an ambient resolver call with files that also cross a thread/async boundary (`ExecutorService`, `CompletableFuture`, `Executors.`, `.submit(`, `@Scheduled`, `ScheduledExecutorService`, `new Thread(`). Ambient `RequestContext` is bound to the originating request thread — once code runs off that thread, the ambient value isn't just theoretically racy, it's structurally wrong. This proxy **re-discovers `LambdaService.java` and `CloudFormationService.java`**, both already confirmed as landmine sources in issues/0008 (medium/high findings), as a sanity check — and surfaces **13 new candidate files**, for zero LLM tokens:

  ```
  services/amazonmq/AmazonMqService.java
  services/appsync/AppSyncService.java
  services/backup/BackupService.java
  services/cloudmap/CloudMapService.java
  services/cloudtrail/CloudTrailLogWriter.java
  services/codedeploy/CodeDeployService.java
  services/eks/EksService.java
  services/floci/ui/FlociUiManager.java
  services/kinesisanalytics/KinesisAnalyticsV2Service.java
  services/msk/MskService.java
  services/mwaa/MwaaService.java
  services/opensearch/OpenSearchService.java
  services/sqs/SqsService.java
  ```

  Reproducible via `sh scripts/static-checks/find-ambient-account-region-candidates.sh`.

**Revised method going forward**: run the static pre-filter first to rank/shortlist candidate files, then spend the Workflow/LLM-sweep budget only on files the pre-filter flags (plus a periodic broader sweep as a backstop, since the filter is a precision tool, not a completeness guarantee — files with no async construct and no direct ambient call can still hide the bug behind an indirection the filter can't see, e.g. a helper method with a generic name). This makes the epic's remaining ~70-module scope far cheaper to work through than issues/0008's blind 26-file sweep was.

## Method (established by issues/0008, reuse for LLM triage of pre-filtered candidates)

`Workflow` tool, one Haiku subagent per file (or small tightly-related file group), each given the exact bug-shape writeup + prioritized landmine categories from `.temp/landmine-sweep.workflow.js`'s `reviewPrompt()`, structured-output JSON schema (`severity`/`confidence`/`failure_scenario` required per finding, explicit calibration rules against manufactured findings), followed by one Sonnet synthesis/dedup pass. Keep the file list bounded (~20-30 files) per sweep run rather than attempting the whole `services/` tree in one shot — issues/0008's 26-file run took 27 agents / ~22 min / ~1.3M subagent tokens, so a full ~70-module sweep at similar density would be a genuinely large token spend and should be run in explicit batches, not as one giant fan-out. With the static pre-filter, the first batch should be the 13 new candidate files above rather than an arbitrary service-domain slice.

## Decision log

- **2026-08-12**: User decided to **hold** all fixing (including issues/0008's own findings) until the in-flight `uc-deploy-all.sh uc-build` verification run (issues/0007 retest) reaches a terminal result, to avoid confounding live verification with unrelated code churn. This epic is opened as a tracking placeholder in the meantime — no new sweep has been launched yet.

## Next

1. Wait for the uc-build run (issues/0007 retest) to finish and for a decision on issues/0008 fix prioritization — this epic's sweeps are additional net-new work, lower priority than confirming the current fix and clearing the known critical/high backlog.
2. When resumed: run `sh scripts/static-checks/find-ambient-account-region-candidates.sh` again (cheap, catches any new files added since 2026-08-12) and LLM-sweep whatever it returns first — the 13 files listed above are the known first batch as of this writing. Only fall back to the "High-likelihood"/"Medium-likelihood" service-domain lists further up once the static-filter backlog is clear.
3. Each sweep's findings get logged as a new `issues/000N-*.md` in the same style as issues/0008 (one consolidated entry per sweep run, not one file per finding), and linked back to this epic.
4. A region-ambient sweep, when it happens, must exclude the known-global services listed above from "no explicit region" findings.
