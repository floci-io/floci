# [epic] systematic account+region-scoping audit across the whole services/ tree

- **Status:** Open — epic/tracking, no sweep beyond the CloudFormation provisioning path run yet
- **Labels:** epic, correctness, multi-account, multi-region
- **Severity:** n/a (epic — tracks a family of findings, each triaged/severity-rated individually)

## Bug pattern

Code that already has an explicit, resolved account or region in scope instead reaches for an
ambient value — `RegionResolver.getAccountId()` / `RegionResolver.getDefaultRegion()`, an
unscoped `RequestContext`, or a shared/static store — rather than using the value it was already
given. The bug is easy to miss because the ambient call almost always returns *something*
plausible; it only produces a visibly wrong answer when the ambient value differs from the
already-resolved one (a launched container acting on behalf of a different account, a resource
created via CloudFormation for a non-default account/region, or any code that crosses a
thread/async boundary after the ambient context was bound).

This shape has been found and fixed three times so far:

- Non-role-assuming launched containers fell back to a placeholder AKID that resolved to
  whichever account was ambient, not the Lambda's real owner (fixed in `291d64084`,
  "resolve launched-container placeholder creds to the function's owning account").
- `LambdaService.createFunction`: CloudFormation had already resolved the real target account for
  a Lambda but never threaded it through creation, so the function got stamped with the ambient
  account instead (fixed in `d83522654`, "stamp CloudFormation-created Lambda functions with the
  resolved account, not the ambient one").
- A 26-file sweep of the CloudFormation provisioning path (provisioners, IAM, EC2, SQS, StackSets,
  Lambda launch/store) found 13 more instances of the same shape; all findings were triaged and
  closed out (see `8f75dacb8` / `5289cc16d`).

That sweep was scoped to the CloudFormation provisioning path only, because that's where the
confirmed live-run failures happened. It did not cover the rest of the service tree under
`src/main/java/io/github/hectorvent/floci/services/`, and it checked only the **account**-ambient
variant — the identical structural flaw applies equally to **region**
(`regionResolver.getDefaultRegion()` read instead of an already-known explicit region).

## How to find candidates

Grep for ambient-resolver call sites, then narrow to files that also cross a thread/async
boundary — ambient `RequestContext` is bound to the originating request thread, so once code runs
off that thread an ambient read is structurally wrong, not just theoretically racy:

```sh
grep -rn 'regionResolver\.getAccountId()\|requestContext\.getAccountId()\|regionResolver\.getDefaultRegion()' src/main/java
```

then cross-reference the hits against files containing `ExecutorService`, `CompletableFuture`,
`Executors.`, `.submit(`, `@Scheduled`, `ScheduledExecutorService`, or `new Thread(`. This is a
precision filter, not a completeness guarantee — a file with no async construct and no direct
ambient call can still hide the bug behind an indirection (e.g. a generically named helper
method), so a periodic broader sweep of the untouched modules below should follow as a backstop.

## Scope

**Already covered:** `services/cloudformation/**` (provisioner registry + all concrete
provisioners), `services/lambda/launcher/ContainerLauncher.java`,
`services/lambda/{LambdaService,LambdaFunctionStore}.java`, `services/iam/IamService.java`,
`core/common/{RegionResolver,RequestContext}.java`,
`core/common/docker/LaunchedContainerAwsEnv.java`.

**Not yet covered — candidate service domains**, grouped by likelihood of hitting this bug shape
(multi-account CFN-provisioned resources first, since that's the pattern with two confirmed
hits):

1. **High-likelihood** (CFN/CDK-provisioned, storage-backed, same shape as the confirmed finds):
   `s3`, `kms`, `route53`, `route53resolver`, `cloudtrail`, `configservice`, `organizations`,
   `ram`, `ssoadmin`, `backup`, `codebuild`, `codepipeline` (their service classes, not just the
   already-covered CFN provisioners), `elbv2`, `autoscaling`, `eventbridge`, `sns`,
   `stepfunctions`.
2. **Medium-likelihood** (multi-account-relevant, less central to CFN-driven stacks):
   `apigateway`, `apigatewayv2`, `appsync`, `cloudfront`, `cognito`, `dynamodb`, `ecr`, `ecs`,
   `eks`, `secretsmanager`, `sqs`, `ssm`.
3. **Lower-likelihood / not CFN-account-scoped by nature** (still worth a pass, but not first):
   everything else under `services/` — `acm`, `amazonmq`, `appconfig`,
   `applicationautoscaling`, `athena`, `batch`, `bcmdataexports`, `bedrockruntime`, `ce`,
   `cloudcontrol`, `cloudmap`, `cloudwatch`, `codedeploy`, `controltower`, `cur`, `docdb`,
   `elasticache`, `elasticbeanstalk`, `emr`, `firehose`, `floci`, `glue`, `iot`, `kinesis`,
   `kinesisanalytics`, `lightsail`, `memorydb`, `msk`, `mwaa`, `neptune`, `opensearch`, `pipes`,
   `pricing`, `rds`, `rdsdata`, `resourcegroupstagging`, `rum`, `s3vectors`, `scheduler`,
   `servicequotas`, `ses`, `textract`, `transcribe`, `transfer`, `wafv2`.

A grep of the current tree for the ambient-call/async-boundary pattern above turns up 13 files as
the current best starting batch:

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

**Region-ambient variant:** a dedicated pass (can run alongside any of the above, or as its own
sweep) specifically hunting for `regionResolver.getDefaultRegion()` reads where an explicit region
was already resolved and in scope. Region wasn't systematically checked even within the files
already covered above.

Per the AWS whitepaper on
[fault isolation boundaries for global services](https://docs.aws.amazon.com/whitepapers/latest/aws-fault-isolation-boundaries/global-services.md),
the following are confirmed global (control plane hosted in one Region, but the resource itself
has no region dimension) and should be excluded from region-ambient findings:

- **IAM** — roles/users/policies are account-wide, not region-keyed.
- **Organizations** — control plane in `us-east-1`; the resource has no region.
- **Route 53 Public/Private DNS hosted zones** — hosted zones themselves have no region
  (this does **not** extend to Route 53 Resolver's DNS Firewall or resolver endpoints/rules,
  which are genuinely per-Region and stay in scope).
- **CloudFront distributions** — global resources.
- **AWS WAF for CloudFront scope only** — WAFv2's `REGIONAL` scope (used with ALB/API Gateway/
  AppSync) is per-Region and must stay in scope.

Everything else in the candidate lists above — including services sometimes assumed to be
global, like ACM (certificates are Regional; only a cert used with CloudFront must live in
`us-east-1`), S3 (bucket names are globally unique, but buckets themselves are Regional), and
SSO/Identity Center (not called out as global by the whitepaper) — stays in normal scope for the
region-ambient sweep.

## Next

1. Audit the 13-file starting batch above for both the account- and region-ambient variant of the
   pattern; log findings the same way the CloudFormation-path sweep did (one consolidated
   `issues/000N-*.md` per sweep run, not one file per finding), linked back to this epic.
2. Re-run the grep above periodically to catch new files added since this epic was opened, and
   work through the "High-likelihood" list once the current batch is clear.
3. When a region-ambient sweep runs, exclude only the confirmed-global services listed above from
   "no explicit region" findings — everything else, including the corrected items, is in scope.
