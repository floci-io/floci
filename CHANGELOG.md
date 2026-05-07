# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **ssm:** Run Command — `SendCommand`, `GetCommandInvocation`, `ListCommands`, `ListCommandInvocations`, `CancelCommand`, `DescribeInstanceInformation`; `UpdateInstanceInformation` (agent registration); `ec2messages` polling protocol (`GetMessages`, `AcknowledgeMessage`, `SendReply`, `FailMessage`, `DeleteMessage`, `GetEndpoint`) so the real `amazon-ssm-agent` running inside EC2 containers can register, receive commands, and report output
- **codedeploy:** Server platform (Phase 4) — on-premises instance management (`RegisterOnPremisesInstance`, `DeregisterOnPremisesInstance`, `GetOnPremisesInstance`, `BatchGetOnPremisesInstances`, `ListOnPremisesInstances`, `AddTagsToOnPremisesInstances`, `RemoveTagsFromOnPremisesInstances`); Server AppSpec YAML parsing; EC2 and on-premises instance resolution by tag filters; full lifecycle event execution (`ApplicationStop` → `DownloadBundle` → `BeforeInstall` → `Install` → `AfterInstall` → `ApplicationStart` → `ValidateService`); SSM Run Command integration for hook script execution with graceful degradation when SSM is unavailable; per-instance `InstanceTarget` tracking in `ListDeploymentTargets`/`BatchGetDeploymentTargets`
- **elbv2:** Lambda targets — ALB listeners can forward to Lambda functions; ALB event format with `queryStringParameters`, `multiValueQueryStringParameters`, `headers`, `multiValueHeaders`, `body`, `isBase64Encoded`; Lambda→ALB response mapping; Lambda target groups always return healthy without HTTP probing

## [1.5.12] - 2026-05-04

### Added

- **apigatewayv2:** WebSocket API support, Update ops, Route/Integration Responses, Models, and Tagging ([#682](https://github.com/floci-io/floci/issues/682))
- **autoscaling:** EC2 Auto Scaling stored-state management API and capacity reconciler ([#681](https://github.com/floci-io/floci/issues/681))
- **ses:** add `TestRenderTemplate` (v1) and `TestRenderEmailTemplate` (v2) ([#692](https://github.com/floci-io/floci/issues/692))
- **glue:** Schema Registry support — registries, schemas, versions, compatibility checks, metadata, tags, and Java SerDe SDK compatibility ([#621](https://github.com/floci-io/floci/issues/621))
- **parity:** LocalStack drop-in compatibility layer ([#696](https://github.com/floci-io/floci/issues/696))

### Fixed

- **sfn:** apply `ItemSelector`/`Parameters` transformation in Map state ([#683](https://github.com/floci-io/floci/issues/683))
- **ecs:** fix delete resource issue ([#685](https://github.com/floci-io/floci/issues/685))
- **lambda:** use function region in container AWS environment ([#687](https://github.com/floci-io/floci/issues/687))
- **cloudformation:** adopt existing DynamoDB tables on stack update ([#689](https://github.com/floci-io/floci/issues/689))
- **cloudformation:** resolve Floci virtual-hosted template URLs ([#690](https://github.com/floci-io/floci/issues/690))
- **docker:** fix `ARG` ordering in compat Dockerfile so `VERSION` build-arg is applied correctly

## [1.5.11] - 2026-05-02

### Added

- **ec2:** real Docker container execution with SSH, UserData, and IMDS support ([#658](https://github.com/floci-io/floci/issues/658))
- **cognito:** implement `AdminRespondToAuthChallenge` operation ([#666](https://github.com/floci-io/floci/issues/666))
- **pipes:** populate SQS message attributes in pipe source records ([#667](https://github.com/floci-io/floci/issues/667))
- **cloudformation:** add `Fn::ImportValue` cross-stack reference resolution ([#669](https://github.com/floci-io/floci/issues/669))
- **elbv2:** ALB proxy, CodeDeploy, and ECS integration ([#677](https://github.com/floci-io/floci/issues/677))
- **lambda:** support `ImageConfig.WorkingDirectory` ([#673](https://github.com/floci-io/floci/issues/673))

### Fixed

- Normalize bare `host:port` Docker host values to prevent URI parse failure ([#665](https://github.com/floci-io/floci/issues/665))
- **athena:** use embedded DNS server to resolve S3 URL used by floci-duck containers ([#672](https://github.com/floci-io/floci/issues/672))
- **iam:** protocol-aware Access Denied response in IAM enforcement filter ([#657](https://github.com/floci-io/floci/issues/657))
- **iam:** read `Action` from form body in IAM enforcement filter ([#655](https://github.com/floci-io/floci/issues/655))
- **lambda:** fix incorrect request size limit ([#674](https://github.com/floci-io/floci/issues/674))
- **sqs:** inject `QueueUrl` into form body for query protocol path-based requests ([#670](https://github.com/floci-io/floci/issues/670))
- **ecr:** properly check if `host-persistent-path` is the name of a Docker volume ([#678](https://github.com/floci-io/floci/issues/678))

## [1.5.10] - 2026-04-30

### Added

- **lambda:** support `ImageConfig` `Command` and `EntryPoint` for container images ([#630](https://github.com/floci-io/floci/issues/630))
- **ses:** add `ConfigurationSet` CRUD on v1 Query and v2 REST JSON protocols ([#631](https://github.com/floci-io/floci/issues/631))
- **cloudformation:** provision `AWS::Pipes::Pipe` resources ([#634](https://github.com/floci-io/floci/issues/634))
- **cognito:** include all standard attributes in `DescribeUserPool` schema ([#642](https://github.com/floci-io/floci/issues/642))
- **ses:** add `SendBulkEmail` (v2) and `SendBulkTemplatedEmail` (v1) ([#645](https://github.com/floci-io/floci/issues/645))
- **cognito:** Custom Auth Flow integration with Lambda triggers ([#646](https://github.com/floci-io/floci/issues/646))
- **codebuild, codedeploy:** implement real Docker-based build execution (phase 2) ([#649](https://github.com/floci-io/floci/issues/649))
- **iam:** seed 23 additional AWS managed execution-role policies ([#650](https://github.com/floci-io/floci/issues/650))
- **dynamodb:** implement `ExportTableToPointInTime`, `DescribeExport`, and `ListExports` ([#653](https://github.com/floci-io/floci/issues/653))
- **cloudformation:** support `AWS::Lambda::EventSourceMapping` resource type ([#654](https://github.com/floci-io/floci/issues/654))

### Fixed

- **docker:** replace `bash /dev/tcp` HEALTHCHECK with `busybox wget` for Alpine runtime compatibility ([#625](https://github.com/floci-io/floci/issues/625))
- **pipes:** resolve `InputTemplate` paths through JSON-encoded string fields ([#635](https://github.com/floci-io/floci/issues/635))
- **dynamodb:** fix GSI query pagination infinite loop when items share a sort key ([#637](https://github.com/floci-io/floci/issues/637))
- **cloudformation:** wrap `ZipFile` inline source in a zip archive before passing to Lambda ([#639](https://github.com/floci-io/floci/issues/639))
- **lambda:** inject log group, log stream, and Floci endpoint env vars into Lambda containers ([#640](https://github.com/floci-io/floci/issues/640))
- **cloudformation:** resolve path-style AWS S3 `TemplateURL` against local S3 ([#641](https://github.com/floci-io/floci/issues/641))
- **eks:** use named Docker volume for k3s data directory to avoid `EINVAL` on macOS APFS ([#643](https://github.com/floci-io/floci/issues/643))
- **apigateway:** support `_custom_id_` tag on `CreateRestApi` ([#644](https://github.com/floci-io/floci/issues/644))
- **apigateway:** implement `GET /restapis/{id}/deployments/{deploymentId}` ([#652](https://github.com/floci-io/floci/issues/652))
- **athena:** connect to floci-duck container via IP instead of DNS ([#648](https://github.com/floci-io/floci/issues/648))

## [1.5.9] - 2026-04-28

### Added

- **elbv2:** Elastic Load Balancing v2 management API — Phase 1 ([#617](https://github.com/floci-io/floci/issues/617))
- **codebuild, codedeploy:** add management APIs ([#622](https://github.com/floci-io/floci/issues/622))

### Fixed

- **cloudformation:** resolve changesets by ARN in `DescribeChangeSet`, `ExecuteChangeSet`, `DeleteChangeSet` ([#608](https://github.com/floci-io/floci/issues/608))
- **lambda:** drop dead pooled containers in `WarmPool.acquire()` before reuse ([#610](https://github.com/floci-io/floci/issues/610))
- **apigateway:** propagate TOKEN authorizer context to `AWS_PROXY` Lambda integrations ([#581](https://github.com/floci-io/floci/issues/581))
- **pipes:** invoke non-Lambda targets per-record instead of batch envelope ([#590](https://github.com/floci-io/floci/issues/590))
- **docker:** avoid host mountinfo false positives in container detection ([#616](https://github.com/floci-io/floci/issues/616))
- **s3:** emit `x-amz-transition-default-minimum-object-size` on lifecycle `GET`/`PUT` ([#615](https://github.com/floci-io/floci/issues/615))
- **s3:** fix OOM on `PutObject` with large payloads ([#620](https://github.com/floci-io/floci/issues/620))
- **kms:** `GetKeyRotationStatus` now returns `false` for asymmetric and HMAC keys ([#618](https://github.com/floci-io/floci/issues/618))
- **lambda:** inject default AWS credentials into Lambda containers ([#623](https://github.com/floci-io/floci/issues/623))
- **ec2:** persist VPC DNS attributes and support `DescribeVpcEndpointServices` ([#624](https://github.com/floci-io/floci/issues/624))

## [1.5.8] - 2026-04-25

### Added

- **pipes:** add filtering, input templates, batch sizes, and DLQ routing ([#576](https://github.com/floci-io/floci/issues/576))
- **cognito:** add `TagResource`, `UntagResource`, `ListTagsForResource` for user pools ([#579](https://github.com/floci-io/floci/issues/579))
- **ses:** implement email template CRUD with stored, inline, and ARN variants ([#573](https://github.com/floci-io/floci/issues/573))
- **sqs:** clear SNS FIFO deduplication cache on `PurgeQueue` ([#594](https://github.com/floci-io/floci/issues/594))
- **athena:** real SQL execution via floci-duck DuckDB sidecar ([#584](https://github.com/floci-io/floci/issues/584))
- **lambda:** embedded DNS server for virtual-hosted S3 URL resolution inside containers ([#585](https://github.com/floci-io/floci/issues/585))
- **lambda:** bind-mount hot-reload via `S3Bucket=hot-reload` ([#601](https://github.com/floci-io/floci/issues/601))

### Fixed

- **dynamodb:** accept full ARN for `TableName` across all operations ([#580](https://github.com/floci-io/floci/issues/580))
- **dynamodb:** enforce `DeletionProtectionEnabled` on create, update, and delete ([#583](https://github.com/floci-io/floci/issues/583))
- **lambda:** support nested Python handler module paths ([#570](https://github.com/floci-io/floci/issues/570))
- **dynamodb:** serialise concurrent mutations and transactions via per-item locks ([#572](https://github.com/floci-io/floci/issues/572))
- **pipes:** return parameters and tags in mutation responses; warn on stream record loss ([#588](https://github.com/floci-io/floci/issues/588))
- **pipes:** read `EventBridgeEventBusParameters` for `Source` and `DetailType` ([#589](https://github.com/floci-io/floci/issues/589))
- **lambda:** parse empty payload without error ([#600](https://github.com/floci-io/floci/issues/600))
- **docker:** make bind-mounted `/var/run/docker.sock` work on all host types ([#602](https://github.com/floci-io/floci/issues/602))
- **lambda:** replace blocking `/next` handler with reactive pattern ([#596](https://github.com/floci-io/floci/issues/596))
- **lambda:** wire `S3VirtualHostFilter` to container-aware DNS suffix ([#598](https://github.com/floci-io/floci/issues/598))
- **s3-control:** accept plain S3 ARN (`arn:aws:s3:::bucket`) in `ListTagsForResource` ([#603](https://github.com/floci-io/floci/issues/603))
- **rds:** fix `DBSubnetGroup` shape, non-master auth pass-through, and volume lifecycle ([#604](https://github.com/floci-io/floci/issues/604))

## [1.5.7] - 2026-04-23

### Fixed

- **docker:** fix Docker image build issue introduced in 1.5.6

## [1.5.6] - 2026-04-23

### Added

- **ses:** SMTP relay support for `SendEmail` and `SendRawEmail` ([#534](https://github.com/floci-io/floci/issues/534))
- **appconfig:** resource tagging and predefined deployment strategies ([#533](https://github.com/floci-io/floci/issues/533))
- **firehose:** implement `DeleteDeliveryStream` API ([#535](https://github.com/floci-io/floci/issues/535))
- **pipes:** EventBridge Pipes service — CRUD API, source polling, and target invocation ([#539](https://github.com/floci-io/floci/issues/539), [#555](https://github.com/floci-io/floci/issues/555))
- **docker:** centralize Docker config and add private registry authentication ([#549](https://github.com/floci-io/floci/issues/549))
- **scheduler:** invoke targets when EventBridge Scheduler schedules fire ([#551](https://github.com/floci-io/floci/issues/551))
- **secretsmanager:** add `UpdateSecretVersionStage` handling ([#545](https://github.com/floci-io/floci/issues/545))
- **cognito, kms:** allow caller-pinned resource IDs via the reserved `floci:override-id` tag ([#568](https://github.com/floci-io/floci/issues/568))
- **sqs:** add option to clear deduplication cache on `PurgeQueue` ([#561](https://github.com/floci-io/floci/issues/561))

### Fixed

- **dynamodb:** return correct attributes for `UPDATED_NEW`/`UPDATED_OLD` on new items ([#538](https://github.com/floci-io/floci/issues/538))
- **lambda:** initialize ESM pollers at startup; fix worker-pool exhaustion in RuntimeApiServer ([#543](https://github.com/floci-io/floci/issues/543))
- **secretsmanager:** fix incorrect update of `AWSPENDING` on secret value update ([#542](https://github.com/floci-io/floci/issues/542))
- **kms:** support `HMAC_*` key specs in `CreateKey` ([#544](https://github.com/floci-io/floci/issues/544))
- **lambda:** add missing `FunctionConfiguration` fields and fix response shape ([#546](https://github.com/floci-io/floci/issues/546))
- **s3:** wrap S3 Control XML errors in `ErrorResponse` wrapper ([#560](https://github.com/floci-io/floci/issues/560))
- **native, acm:** fix native image reflection for ACM ([#559](https://github.com/floci-io/floci/issues/559))
- **s3:** enforce conditional writes ([#566](https://github.com/floci-io/floci/issues/566))

## [1.5.5] - 2026-04-19

### Added

- **eks:** implement EKS service with real k3s data plane support ([#493](https://github.com/floci-io/floci/issues/493))
- **s3:** implement static website hosting with index document redirection ([#507](https://github.com/floci-io/floci/issues/507))
- **lambda:** reactive S3-to-Lambda sync for hot-reloading ([#509](https://github.com/floci-io/floci/issues/509))
- **dynamodb:** support `ReturnValuesOnConditionCheckFailure` ([#505](https://github.com/floci-io/floci/issues/505))
- **eventbridge:** add `PutPermission` and `RemovePermission` support ([#499](https://github.com/floci-io/floci/issues/499))
- **cognito:** implement `AdminEnableUser` and `AdminDisableUser` ([#514](https://github.com/floci-io/floci/issues/514))
- **cognito:** implement `AdminResetUserPassword` ([#516](https://github.com/floci-io/floci/issues/516))
- **kinesis:** support `AT_TIMESTAMP` shard iterator type ([#520](https://github.com/floci-io/floci/issues/520))
- **opensearch:** real OpenSearch container support via Docker image ([#528](https://github.com/floci-io/floci/issues/528))
- **secretsmanager:** add version stages handling in `PutSecretValue` ([#527](https://github.com/floci-io/floci/issues/527))
- **s3:** preserve explicit object server-side-encryption headers ([#515](https://github.com/floci-io/floci/issues/515))

### Fixed

- **dynamodb:** support arithmetic in SET expressions ([#480](https://github.com/floci-io/floci/issues/480))
- **sqs:** include `MD5OfMessageAttributes` in `SendMessageBatch` JSON response ([#496](https://github.com/floci-io/floci/issues/496))
- **cloudformation:** forward Lambda environment variables during stack provisioning ([#510](https://github.com/floci-io/floci/issues/510))
- **kms:** support `ECC_SECG_P256K1` via BouncyCastle JCA ([#502](https://github.com/floci-io/floci/issues/502))
- **dynamodb:** validate sort key in `buildItemKey` to prevent item collisions ([#506](https://github.com/floci-io/floci/issues/506))
- **storage:** make `scan()` return a mutable list for non-in-memory backends ([#517](https://github.com/floci-io/floci/issues/517))
- **ses:** align `/_aws/ses` inspection endpoint with LocalStack behavior ([#512](https://github.com/floci-io/floci/issues/512))
- **lifecycle:** run shutdown hooks before HTTP server stops ([#519](https://github.com/floci-io/floci/issues/519))
- **kinesis:** return real `ShardId` in `PutRecord` and `PutRecords` responses ([#518](https://github.com/floci-io/floci/issues/518))
- **lambda:** restore create-copy-start ordering for provided runtimes ([#524](https://github.com/floci-io/floci/issues/524))
- **lambda:** fix container lifecycle on timeout ([#529](https://github.com/floci-io/floci/issues/529))
- **lambda:** destroy container handle on interrupt and generic exceptions ([#530](https://github.com/floci-io/floci/issues/530))
- **lambda:** wake blocked `/next` poller and drain queue on `RuntimeApiServer.stop()` ([#531](https://github.com/floci-io/floci/issues/531))

### Security

- **s3:** harden path traversal defenses in `S3Service` and `S3Controller` ([#508](https://github.com/floci-io/floci/issues/508))

## [1.5.4] - 2026-04-17

### Added

- **iam:** seed AWS managed policies at startup ([#400](https://github.com/floci-io/floci/issues/400))
- **s3:** preserve `Content-Disposition` on object responses ([#408](https://github.com/floci-io/floci/issues/408))
- **sfn:** add SQS `sendMessage` AWS SDK integrations ([#409](https://github.com/floci-io/floci/issues/409))
- **kinesis:** implement `EnableEnhancedMonitoring` and `DisableEnhancedMonitoring` ([#417](https://github.com/floci-io/floci/issues/417))
- **msk:** implement Amazon MSK service with Redpanda orchestration ([#419](https://github.com/floci-io/floci/issues/419))
- **dynamodb:** add `EnableKinesisStreamingDestination` support ([#427](https://github.com/floci-io/floci/issues/427))
- **lambda:** enforce reserved and per-region concurrency ([#424](https://github.com/floci-io/floci/issues/424))
- **eventbridge:** implement `TagResource` and `UntagResource` ([#453](https://github.com/floci-io/floci/issues/453))
- **cloudwatch:** implement `GetMetricData` ([#451](https://github.com/floci-io/floci/issues/451))
- **cognito:** create, list, and delete user pool client secrets ([#345](https://github.com/floci-io/floci/issues/345))
- **ec2:** implement `CreateVolume`, `DescribeVolumes`, `DeleteVolume` ([#455](https://github.com/floci-io/floci/issues/455))
- **athena, glue, firehose:** implement local Data Lake stack with real SQL execution ([#429](https://github.com/floci-io/floci/issues/429))
- **tagging:** implement Resource Groups Tagging API ([#459](https://github.com/floci-io/floci/issues/459))
- **bedrock-runtime:** stub `Converse` and `InvokeModel` ([#486](https://github.com/floci-io/floci/issues/486))
- **kinesis:** implement `UpdateStreamMode` ([#488](https://github.com/floci-io/floci/issues/488))
- **lambda:** support `ScalingConfig.MaximumConcurrency` on SQS event source mappings ([#490](https://github.com/floci-io/floci/issues/490))
- **lambda:** add `UpdateFunctionConfiguration` API ([#472](https://github.com/floci-io/floci/issues/472))
- **dynamodb:** support `UPDATED_OLD` and `UPDATED_NEW` `ReturnValues` ([#477](https://github.com/floci-io/floci/issues/477))

### Fixed

- **elasticache:** scope single-arg `AUTH` to default user per Redis ACL spec ([#390](https://github.com/floci-io/floci/issues/390))
- **auth:** bind SigV4 token identity and use timing-safe comparison ([#389](https://github.com/floci-io/floci/issues/389))
- **apigatewayv2:** add missing JSON handler operations and `stageName` auto-deploy ([#393](https://github.com/floci-io/floci/issues/393))
- **cloudformation:** populate SQS queue ARN in resource attributes ([#396](https://github.com/floci-io/floci/issues/396))
- **elasticache, rds:** prevent orphaned Docker containers on shutdown and restart ([#398](https://github.com/floci-io/floci/issues/398))
- **s3:** remove XML declaration from `GetBucketLocation` response ([#403](https://github.com/floci-io/floci/issues/403))
- **lambda:** resolve handler paths with subdirectories; skip validation for dotnet runtimes ([#404](https://github.com/floci-io/floci/issues/404))
- **iam:** enforce IAM when enabled ([#411](https://github.com/floci-io/floci/issues/411))
- **dynamodb:** implement `DELETE` action for set attributes ([#415](https://github.com/floci-io/floci/issues/415))
- **sqs:** use queue-level `VisibilityTimeout` as fallback in `ReceiveMessage` ([#413](https://github.com/floci-io/floci/issues/413))
- **lambda:** raise Jackson `maxStringLength` for large inline zip uploads ([#418](https://github.com/floci-io/floci/issues/418))
- **dynamodb:** support `REMOVE` for nested map paths ([#421](https://github.com/floci-io/floci/issues/421))
- **cognito:** return only description fields in `ListUserPoolClients` ([#420](https://github.com/floci-io/floci/issues/420))
- **s3:** honor canned object ACLs on write paths ([#422](https://github.com/floci-io/floci/issues/422))
- **ecr:** fall back to named volume for registry data when `host-persistent-path` is unset inside containers ([#442](https://github.com/floci-io/floci/issues/442))
- **kinesis:** return time-based `MillisBehindLatest` ([#444](https://github.com/floci-io/floci/issues/444))
- **lambda:** accept name, partial ARN, and full ARN for `{FunctionName}` path parameter ([#450](https://github.com/floci-io/floci/issues/450))
- **dynamodb:** accept newline as `UpdateExpression` clause separator ([#449](https://github.com/floci-io/floci/issues/449))
- **cognito:** add `aud` claim to ID tokens ([#454](https://github.com/floci-io/floci/issues/454))
- **s3:** implement bucket ownership controls ([#456](https://github.com/floci-io/floci/issues/456))
- **dynamodb:** support continuous backups and PITR actions ([#458](https://github.com/floci-io/floci/issues/458))
- **kinesis:** accept AWS SDK v2 CBOR content type ([#457](https://github.com/floci-io/floci/issues/457))
- **dynamodb:** fix expression evaluation, `UpdateExpression` paths, and `ConsumedCapacity` ([#197](https://github.com/floci-io/floci/issues/197))
- **lambda:** include `LastUpdateStatus` in function configuration responses ([#463](https://github.com/floci-io/floci/issues/463))
- Add log rotation by default to all Floci-launched containers ([#466](https://github.com/floci-io/floci/issues/466))
- **sqs:** honor queue-level `DelaySeconds` on FIFO queues ([#476](https://github.com/floci-io/floci/issues/476))
- **ecr:** fix port and network issues ([#483](https://github.com/floci-io/floci/issues/483))
- **s3-control:** accept URL-encoded ARNs and return XML errors ([#491](https://github.com/floci-io/floci/issues/491))

## [1.5.3] - 2026-04-12

### Added

- **appconfig:** new AppConfig service ([#324](https://github.com/floci-io/floci/issues/324))
- **ecr:** new ECR service — push, pull, manage repositories and images ([#337](https://github.com/floci-io/floci/issues/337))
- **docker:** add `HEALTHCHECK` to all Floci Dockerfiles ([#328](https://github.com/floci-io/floci/issues/328))
- **lambda:** add `PutFunctionConcurrency` stub ([#325](https://github.com/floci-io/floci/issues/325))

### Changed

- Unify service metadata behind a descriptor-backed catalog for enablement, routing, and storage lookups ([#357](https://github.com/floci-io/floci/issues/357))

### Fixed

- **s3:** return XML error responses for presigned POST failures ([#327](https://github.com/floci-io/floci/issues/327))
- **sqs:** make per-queue message operations atomic ([#333](https://github.com/floci-io/floci/issues/333))
- **eventbridge:** apply `InputPath` when delivering events to targets ([#335](https://github.com/floci-io/floci/issues/335))
- **cloudformation:** topologically sort resources before provisioning ([#332](https://github.com/floci-io/floci/issues/332))
- **s3:** return empty `LocationConstraint` for `us-east-1` buckets ([#336](https://github.com/floci-io/floci/issues/336))
- **dynamodb:** attach `X-Amz-Crc32` header to JSON protocol responses ([#347](https://github.com/floci-io/floci/issues/347))
- **cognito:** return sub UUID as `UserSub` in `SignUp` response ([#351](https://github.com/floci-io/floci/issues/351))
- **kinesis:** accept same-value `Increase`/`DecreaseStreamRetentionPeriod` ([#352](https://github.com/floci-io/floci/issues/352))
- **sns:** recognize attributes set during subscription creation ([#353](https://github.com/floci-io/floci/issues/353))
- **ecr, s3:** fix CDK compatibility and resolve macOS ECR port conflict ([#354](https://github.com/floci-io/floci/issues/354))
- **kms:** implement real asymmetric sign/verify and `GetPublicKey` ([#355](https://github.com/floci-io/floci/issues/355))
- **eventbridge:** implement advanced content filtering operators ([#356](https://github.com/floci-io/floci/issues/356))
- **cognito:** correct HMAC signature computation in `USER_SRP_AUTH` ([#358](https://github.com/floci-io/floci/issues/358))
- **s3:** include non-versioned objects in `ListObjectVersions` response ([#359](https://github.com/floci-io/floci/issues/359))
- **secretsmanager:** resolve partial ARNs without random suffix ([#360](https://github.com/floci-io/floci/issues/360))
- **lambda:** correct Function URL config path from `/url-config` to `/url` ([#364](https://github.com/floci-io/floci/issues/364))
- **elasticache:** scope user auth to groups, use `StorageFactory`, throw `NotFoundFault` ([#367](https://github.com/floci-io/floci/issues/367))
- **apigatewayv2:** fix route matching for path-parameter routes ([#368](https://github.com/floci-io/floci/issues/368))
- **s3:** implement S3 Control `ListTagsForResource`, `TagResource`, `UntagResource` ([#363](https://github.com/floci-io/floci/issues/363))
- **cloudformation:** resolve stacks by ARN in addition to name ([#386](https://github.com/floci-io/floci/issues/386))

## [1.5.2] - 2026-04-10

### Added

- **kinesis:** add `IncreaseStreamRetentionPeriod` and `DecreaseStreamRetentionPeriod` ([#305](https://github.com/floci-io/floci/issues/305))
- **kinesis:** resolve stream name from `StreamARN` parameter ([#304](https://github.com/floci-io/floci/issues/304))
- **kms:** add `GetKeyRotationStatus`, `EnableKeyRotation`, and `DisableKeyRotation` ([#290](https://github.com/floci-io/floci/issues/290))
- **s3:** preserve `Cache-Control` header on `PutObject`, `GetObject`, `HeadObject`, and `CopyObject` ([#313](https://github.com/floci-io/floci/issues/313))

### Fixed

- **apigateway:** implement v2 management API and CloudFormation provisioning ([#323](https://github.com/floci-io/floci/issues/323))
- **cloudwatch:** implement tagging support for metrics and alarms ([#320](https://github.com/floci-io/floci/issues/320))
- **dynamodb:** handle null for Java AWS SDK v2 DynamoDB `EnhancedClient` ([#309](https://github.com/floci-io/floci/issues/309))
- **dynamodb:** implement `list_append` with `if_not_exists` support ([#317](https://github.com/floci-io/floci/issues/317))
- **dynamodb:** remove duplicate `list_append` handler that breaks nested expressions ([#321](https://github.com/floci-io/floci/issues/321))
- **rds:** `DescribeDBInstances` returns empty results due to wrong XML element names and missing `Filters` support ([#319](https://github.com/floci-io/floci/issues/319))
- **s3:** preserve leading slashes in object keys to prevent key collisions ([#286](https://github.com/floci-io/floci/issues/286))
- Support LocalStack-compatible `_user_request_` URL for API Gateway execution ([#314](https://github.com/floci-io/floci/issues/314))

## [1.5.1] - 2026-04-09

### Fixed

- Native image build failure due to `SecureRandom` in `CognitoSrpHelper`

## [1.5.0] - 2026-04-09

### Added

- **cloudformation:** add `AWS::Events::Rule` provisioning support ([#261](https://github.com/floci-io/floci/issues/261))
- **eventbridge:** add `InputTransformer` support and S3 event notifications ([#294](https://github.com/floci-io/floci/issues/294))
- **dynamodb:** load persisted DynamoDB streams on startup ([#299](https://github.com/floci-io/floci/issues/299))

### Fixed

- Native build: append `-march=x86-64-v2` for amd64 compatibility ([#303](https://github.com/floci-io/floci/issues/303))
- **dynamodb:** `DescribeTable` returns `Projection.NonKeyAttributes` ([#300](https://github.com/floci-io/floci/issues/300))
- **rds:** implement missing resource identifiers and fix filtering ([#302](https://github.com/floci-io/floci/issues/302))
- **s3:** implement S3 Lambda notifications ([#278](https://github.com/floci-io/floci/issues/278))
- **cognito:** implement SRP-6a authentication ([#298](https://github.com/floci-io/floci/issues/298))
- **s3:** use case-insensitive field lookup for presigned POST policy validation ([#289](https://github.com/floci-io/floci/issues/289))
- **s3:** use `ConfigProvider` for runtime config lookup in `S3VirtualHostFilter` ([#288](https://github.com/floci-io/floci/issues/288))
- Register Xerces XML resource bundles for native image ([#296](https://github.com/floci-io/floci/issues/296))

## [1.4.0] - 2026-04-08

### Added

- **kms:** add `GetKeyPolicy`, `PutKeyPolicy`, and fix `CreateKey` tag handling ([#280](https://github.com/floci-io/floci/issues/280))
- **ses:** add SES V2 REST JSON protocol support ([#265](https://github.com/floci-io/floci/issues/265))
- **lambda:** add missing runtimes and fix handler validation ([#256](https://github.com/floci-io/floci/issues/256))
- **scheduler:** add EventBridge Scheduler service ([#260](https://github.com/floci-io/floci/issues/260))
- **secretsmanager:** add `BatchGetSecretValue` support ([#264](https://github.com/floci-io/floci/issues/264))
- **sfn:** nested state machine execution and activity support ([#266](https://github.com/floci-io/floci/issues/266))
- Use AWS-specific content type in all JSON-based controller responses ([#240](https://github.com/floci-io/floci/issues/240))

### Fixed

- **dynamodb:** add `list_append` support to update expressions ([#277](https://github.com/floci-io/floci/issues/277))
- Default shell executable to `/bin/sh` for Alpine compatibility ([#241](https://github.com/floci-io/floci/issues/241))
- **lambda:** drain warm pool containers on server shutdown ([#274](https://github.com/floci-io/floci/issues/274))
- **dynamodb:** support `add` function with multiple values ([#263](https://github.com/floci-io/floci/issues/263))
- Handle base64-encoded ACM certificate imports ([#248](https://github.com/floci-io/floci/issues/248))
- **dynamodb:** include `ProvisionedThroughput` in GSI responses ([#273](https://github.com/floci-io/floci/issues/273))
- Return 400 when encoded S3 copy source is malformed ([#244](https://github.com/floci-io/floci/issues/244))
- **cognito:** resolve auth, token, and user lookup issues ([#279](https://github.com/floci-io/floci/issues/279))
- **s3:** enforce presigned POST policy conditions (`eq`, `starts-with`, `content-type`) ([#203](https://github.com/floci-io/floci/issues/203))
- **s3:** fix versioning `IsTruncated`, `PublicAccessBlock`, `ListObjectsV2` pagination, and Kubernetes virtual host routing ([#276](https://github.com/floci-io/floci/issues/276))

## [1.3.0] - 2026-04-06

### Added

- **ec2:** add EC2 service with 61 operations, integration tests, and documentation ([#213](https://github.com/floci-io/floci/issues/213))
- **ecs:** add ECS service ([#209](https://github.com/floci-io/floci/issues/209))
- **dynamodb:** add `ScanFilter` support for `Scan` operation ([#175](https://github.com/floci-io/floci/issues/175))
- **eventbridge:** forward resources array and support resources pattern matching ([#210](https://github.com/floci-io/floci/issues/210))
- **lambda:** add `AddPermission`, `GetPolicy`, `ListTags`, `ListLayerVersions` endpoints ([#223](https://github.com/floci-io/floci/issues/223))
- **sfn:** JSONata improvements, `States.*` intrinsics, DynamoDB `ConditionExpression`, `StartSyncExecution` ([#205](https://github.com/floci-io/floci/issues/205))
- Add `GlobalSecondaryIndexUpdates` support in DynamoDB `UpdateTable` ([#222](https://github.com/floci-io/floci/issues/222))
- Add scheduled rules support for EventBridge Rules ([#217](https://github.com/floci-io/floci/issues/217))

### Fixed

- Fall back to Docker bridge IP when `host.docker.internal` is unresolvable ([#216](https://github.com/floci-io/floci/issues/216))
- **lambda:** copy code to `TASK_DIR` for provided runtimes ([#206](https://github.com/floci-io/floci/issues/206))
- **lambda:** honor `ReportBatchItemFailures` in SQS ESM ([#208](https://github.com/floci-io/floci/issues/208))
- **lambda:** support `Code.S3Bucket` + `Code.S3Key` in `CreateFunction` and `UpdateFunctionCode` ([#219](https://github.com/floci-io/floci/issues/219))
- **ses:** add missing `Result` element to query protocol responses ([#207](https://github.com/floci-io/floci/issues/207))
- **sns:** make `Subscribe` idempotent for same `topic+protocol+endpoint` ([#185](https://github.com/floci-io/floci/issues/185))

## [1.2.0] - 2026-04-04

### Added

- **cloudwatch-logs:** add `ListTagsForResource`, `TagResource`, and `UntagResource` ([#172](https://github.com/hectorvent/floci/issues/172))
- **cognito:** add group management support ([#149](https://github.com/hectorvent/floci/issues/149))
- **s3:** support `Filter` rules in `PutBucketNotificationConfiguration` ([#178](https://github.com/hectorvent/floci/issues/178))
- **lambda:** implement `ListVersionsByFunction` API ([#193](https://github.com/hectorvent/floci/issues/193))
- Officially support Docker named volumes for native images ([#155](https://github.com/hectorvent/floci/issues/155))
- Health endpoint ([#139](https://github.com/hectorvent/floci/issues/139))
- Implement `UploadPartCopy` for S3 multipart uploads ([#98](https://github.com/hectorvent/floci/issues/98))
- Support `GenerateSecretString` and `Description` for `AWS::SecretsManager::Secret` in CloudFormation ([#176](https://github.com/hectorvent/floci/issues/176))
- Support GSI and LSI in CloudFormation DynamoDB table provisioning ([#125](https://github.com/hectorvent/floci/issues/125))
- Add CloudFormation `Fn::FindInMap` and `Mappings` support ([#101](https://github.com/hectorvent/floci/issues/101))
- **lifecycle:** add support for startup and shutdown initialization hooks ([#128](https://github.com/hectorvent/floci/issues/128))
- **s3:** add conditional request headers (`If-Match`, `If-None-Match`, `If-Modified-Since`, `If-Unmodified-Since`) ([#48](https://github.com/hectorvent/floci/issues/48))
- **s3:** add presigned POST upload support ([#120](https://github.com/hectorvent/floci/issues/120))
- **s3:** add `Range` header support for `GetObject` ([#44](https://github.com/hectorvent/floci/issues/44))
- **sfn:** add DynamoDB AWS SDK integration and complete optimized `updateItem` ([#103](https://github.com/hectorvent/floci/issues/103))
- **apigateway:** OpenAPI/Swagger import, models, and request validation ([#113](https://github.com/hectorvent/floci/issues/113))
- **apigateway:** add AWS integration type for REST v1 ([#108](https://github.com/hectorvent/floci/issues/108))

### Fixed

- **cognito:** auto-generate `sub`, fix JWT sub claim, add `AdminUserGlobalSignOut` ([#183](https://github.com/hectorvent/floci/issues/183))
- **cognito:** enrich User Pool responses and implement `MfaConfig` stub ([#198](https://github.com/hectorvent/floci/issues/198))
- **cognito:** OAuth/OIDC parity for RS256/JWKS, `/oauth2/token`, and OAuth app-client settings ([#97](https://github.com/hectorvent/floci/issues/97))
- Globally inject AWS `request-id` headers for SDK compatibility ([#146](https://github.com/hectorvent/floci/issues/146))
- Defer startup hooks until HTTP server is ready ([#159](https://github.com/hectorvent/floci/issues/159))
- **dynamodb:** fix `FilterExpression` for `BOOL` types, List/Set `contains`, and nested attribute paths ([#137](https://github.com/hectorvent/floci/issues/137))
- **lambda:** copy function code to `/var/runtime` for provided runtimes ([#114](https://github.com/hectorvent/floci/issues/114))
- Resolve CloudFormation Lambda `Code.S3Key` base64 decode error ([#62](https://github.com/hectorvent/floci/issues/62))
- Resolve numeric `ExpressionAttributeNames` in DynamoDB expressions ([#192](https://github.com/hectorvent/floci/issues/192))
- Return stable cursor tokens in `GetLogEvents` to fix SDK pagination loop ([#184](https://github.com/hectorvent/floci/issues/184))
- **s3:** evaluate S3 CORS against incoming HTTP requests ([#131](https://github.com/hectorvent/floci/issues/131))
- **s3:** fix list parts for multipart upload ([#164](https://github.com/hectorvent/floci/issues/164))
- **s3:** persist `Content-Encoding` header on S3 objects ([#57](https://github.com/hectorvent/floci/issues/57))
- **s3:** prevent `S3VirtualHostFilter` from hijacking non-S3 requests ([#199](https://github.com/hectorvent/floci/issues/199))
- **s3:** resolve file/folder name collision on persistent filesystem ([#134](https://github.com/hectorvent/floci/issues/134))
- **s3:** return `CommonPrefixes` in `ListObjects` when delimiter is specified ([#133](https://github.com/hectorvent/floci/issues/133))
- **secretsmanager:** return `KmsKeyId` in `DescribeSecret` and improve `ListSecrets` ([#195](https://github.com/hectorvent/floci/issues/195))
- **sns:** enforce `FilterPolicy` on message delivery ([#53](https://github.com/hectorvent/floci/issues/53))
- **sns:** honor `RawMessageDelivery` attribute for SQS subscriptions ([#54](https://github.com/hectorvent/floci/issues/54))
- **sns:** pass `messageDeduplicationId` from FIFO topics to SQS FIFO queues ([#171](https://github.com/hectorvent/floci/issues/171))
- **sqs:** route queue URL path requests to SQS handler ([#153](https://github.com/hectorvent/floci/issues/153))
- **sqs:** support binary message attributes and fix `MD5OfMessageAttributes` ([#168](https://github.com/hectorvent/floci/issues/168))
- **sqs:** translate Query-protocol error codes to JSON `__type` equivalents ([#59](https://github.com/hectorvent/floci/issues/59))
- Support DynamoDB `Query` `BETWEEN` and `ScanIndexForward=false` ([#160](https://github.com/hectorvent/floci/issues/160))

## [1.1.0] - 2026-03-31

### Added

- **acm:** add ACM certificate management service ([#21](https://github.com/hectorvent/floci/issues/21))
- Add `HOSTNAME_EXTERNAL` support for multi-container Docker setups ([#82](https://github.com/hectorvent/floci/issues/82))
- Add JSONata query language support for Step Functions ([#84](https://github.com/hectorvent/floci/issues/84))
- Add Kinesis `ListShards` operation ([#61](https://github.com/hectorvent/floci/issues/61))
- **opensearch:** add OpenSearch service emulation ([#132](https://github.com/hectorvent/floci/issues/132))
- **ses:** add SES (Simple Email Service) emulation ([#14](https://github.com/hectorvent/floci/issues/14))
- Add virtual host support for S3 bucket routing ([#88](https://github.com/hectorvent/floci/issues/88))
- **apigateway:** add AWS integration type for API Gateway REST v1 ([#108](https://github.com/hectorvent/floci/issues/108))
- **apigateway:** OpenAPI/Swagger import, models, and request validation ([#113](https://github.com/hectorvent/floci/issues/113))
- Docker image with AWS CLI (`floci:x.y.z-aws`) ([#95](https://github.com/hectorvent/floci/issues/95))
- Implement `GetRandomPassword` for Secrets Manager ([#80](https://github.com/hectorvent/floci/issues/80))
- **s3:** add presigned POST upload support ([#120](https://github.com/hectorvent/floci/issues/120))
- **s3:** add `Range` header support for `GetObject` ([#44](https://github.com/hectorvent/floci/issues/44))
- **s3:** add conditional request headers ([#48](https://github.com/hectorvent/floci/issues/48))
- **sfn:** add DynamoDB AWS SDK integration ([#103](https://github.com/hectorvent/floci/issues/103))

### Fixed

- Added `versionId` to S3 notifications for versioning-enabled buckets ([#135](https://github.com/hectorvent/floci/issues/135))
- Align S3 `CreateBucket` and `HeadBucket` region behavior with AWS ([#75](https://github.com/hectorvent/floci/issues/75))
- DynamoDB table creation compatibility with Terraform AWS provider v6 ([#89](https://github.com/hectorvent/floci/issues/89))
- **dynamodb:** apply filter expressions in `Query` ([#123](https://github.com/hectorvent/floci/issues/123))
- **dynamodb:** respect `if_not_exists` for `update_item` ([#102](https://github.com/hectorvent/floci/issues/102))
- Fix S3 `NoSuchKey` for non-ASCII keys ([#112](https://github.com/hectorvent/floci/issues/112))
- **kms:** allow ARN and alias to encrypt ([#69](https://github.com/hectorvent/floci/issues/69))
- Resolve compatibility test failures across multiple services ([#109](https://github.com/hectorvent/floci/issues/109))
- **s3:** allow upload up to 512 MB by default ([#110](https://github.com/hectorvent/floci/issues/110))
- **sns:** add `PublishBatch` support to JSON protocol handler
- Storage load after backend is created ([#71](https://github.com/hectorvent/floci/issues/71))

## [1.0.11] - 2026-03-24

### Fixed

- **s3:** add `GetObjectAttributes` and metadata parity ([#29](https://github.com/hectorvent/floci/issues/29))

## [1.0.10] - 2026-03-24

### Fixed

- **s3:** return `versionId` in `CompleteMultipartUpload` response ([#35](https://github.com/hectorvent/floci/issues/35))

## [1.0.9] - 2026-03-24

### Added

- **lambda:** add Ruby runtime support ([#18](https://github.com/hectorvent/floci/issues/18))

## [1.0.8] - 2026-03-24

### Fixed

- **s3:** return `NoSuchVersion` error for non-existent `versionId`

## [1.0.7] - 2026-03-24

### Fixed

- **s3:** fix unit test error

## [1.0.6] - 2026-03-24

### Fixed

- **s3:** truncate `LastModified` timestamps to second precision ([#24](https://github.com/hectorvent/floci/issues/24))

## [1.0.5] - 2026-03-23

### Fixed

- **s3:** fix `CreateBucket` response format for Rust SDK compatibility ([#11](https://github.com/hectorvent/floci/issues/11))

## [1.0.4] - 2026-03-20

### Fixed

- **ci:** fix Docker build on native pipeline
- **ci:** fix workflow artifact download path

## [1.0.2] - 2026-03-15

### Fixed

- **ci:** fix Docker build action trigger

## [1.0.1] - 2026-03-15

### Fixed

- **ci:** fix GitHub Actions workflow trigger

## [1.0.0] - 2026-03-15

Initial public release of Floci — a fast, free, open-source local AWS emulator.

### Added

- SSM, SQS, SNS, SES, S3, DynamoDB, Lambda, API Gateway, Cognito, KMS, Kinesis, Secrets Manager,
  CloudFormation, Step Functions, IAM, STS, ElastiCache, RDS, EventBridge, and CloudWatch emulation
- AWS SDK and CLI wire-protocol compatibility on port 4566
- Native binary and JVM Docker images
- In-memory, persistent, hybrid, and WAL storage modes

---

[Unreleased]: https://github.com/floci-io/floci/compare/1.5.12...HEAD
[1.5.12]: https://github.com/floci-io/floci/compare/1.5.11...1.5.12
[1.5.11]: https://github.com/floci-io/floci/compare/1.5.10...1.5.11
[1.5.10]: https://github.com/floci-io/floci/compare/1.5.9...1.5.10
[1.5.9]: https://github.com/floci-io/floci/compare/1.5.8...1.5.9
[1.5.8]: https://github.com/floci-io/floci/compare/1.5.7...1.5.8
[1.5.7]: https://github.com/floci-io/floci/compare/1.5.5...1.5.7
[1.5.6]: https://github.com/floci-io/floci/compare/1.5.5...1.5.7
[1.5.5]: https://github.com/floci-io/floci/compare/1.5.4...1.5.5
[1.5.4]: https://github.com/floci-io/floci/compare/1.5.3...1.5.4
[1.5.3]: https://github.com/floci-io/floci/compare/1.5.2...1.5.3
[1.5.2]: https://github.com/floci-io/floci/compare/1.5.1...1.5.2
[1.5.1]: https://github.com/floci-io/floci/compare/1.5.0...1.5.1
[1.5.0]: https://github.com/floci-io/floci/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/floci-io/floci/compare/1.3.0...1.4.0
[1.3.0]: https://github.com/floci-io/floci/compare/1.2.0...1.3.0
[1.2.0]: https://github.com/hectorvent/floci/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/hectorvent/floci/compare/1.0.11...1.1.0
[1.0.11]: https://github.com/hectorvent/floci/compare/1.0.10...1.0.11
[1.0.10]: https://github.com/hectorvent/floci/compare/1.0.9...1.0.10
[1.0.9]: https://github.com/hectorvent/floci/compare/1.0.8...1.0.9
[1.0.8]: https://github.com/hectorvent/floci/compare/1.0.7...1.0.8
[1.0.7]: https://github.com/hectorvent/floci/compare/1.0.6...1.0.7
[1.0.6]: https://github.com/hectorvent/floci/compare/1.0.5...1.0.6
[1.0.5]: https://github.com/hectorvent/floci/compare/1.0.4...1.0.5
[1.0.4]: https://github.com/hectorvent/floci/compare/1.0.3...1.0.4
[1.0.2]: https://github.com/hectorvent/floci/compare/1.0.1...1.0.2
[1.0.1]: https://github.com/hectorvent/floci/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/hectorvent/floci/releases/tag/1.0.0
