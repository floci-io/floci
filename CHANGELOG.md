# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.5.26] - 2026-06-19

### Added

- **cloudformation:** provision EC2 instances ([#1366](https://github.com/floci-io/floci/pull/1366)); RDS resources ([#1367](https://github.com/floci-io/floci/pull/1367)); EKS clusters and node groups ([#1368](https://github.com/floci-io/floci/pull/1368)); CloudWatch Logs log groups ([#1369](https://github.com/floci-io/floci/pull/1369)); CloudWatch metric alarms ([#1371](https://github.com/floci-io/floci/pull/1371)); Auto Scaling groups and launch configurations ([#1372](https://github.com/floci-io/floci/pull/1372)); Kinesis data streams ([#1370](https://github.com/floci-io/floci/pull/1370)); Kinesis Firehose delivery streams ([#1399](https://github.com/floci-io/floci/pull/1399)); add `Fn::GetAZs` and `Fn::Cidr` intrinsics ([#1365](https://github.com/floci-io/floci/pull/1365))
- **docdb:** Amazon DocumentDB service emulation ([#1341](https://github.com/floci-io/floci/pull/1341))
- **autoscaling:** preserve policy and mixed-instances parity ([#1437](https://github.com/floci-io/floci/pull/1437)); support group reconciliation and instance refresh ([#1393](https://github.com/floci-io/floci/pull/1393))
- **ec2:** support Spot Instance request actions ([#1291](https://github.com/floci-io/floci/pull/1291))
- **ssm:** add `DescribePatchBaselines` and `GetDefaultPatchBaseline` ([#1404](https://github.com/floci-io/floci/pull/1404)); execute run commands in EC2 containers ([#1387](https://github.com/floci-io/floci/pull/1387))
- **kms:** add `EnableKey` support ([#1381](https://github.com/floci-io/floci/pull/1381))
- **cognito:** align user pool client config APIs with AWS behavior ([#1360](https://github.com/floci-io/floci/pull/1360))
- **iam:** provisioning preflight support ([#1250](https://github.com/floci-io/floci/pull/1250))
- **ui:** serve a landing page and launch the floci-ui console on demand ([#1313](https://github.com/floci-io/floci/pull/1313))
- **aws:** expand the EC2 and RDS availability catalogs ([#1391](https://github.com/floci-io/floci/pull/1391))

### Fixed

- **cloudformation:** record a `REVIEW_IN_PROGRESS` event on change-set creation ([#1431](https://github.com/floci-io/floci/pull/1431)); create the stage from an `AWS::ApiGateway::Deployment` inline `StageName` ([#1400](https://github.com/floci-io/floci/pull/1400))
- **lambda:** redrive failed SQS event-source-mapping messages to the DLQ ([#1419](https://github.com/floci-io/floci/pull/1419)); return the `{"status":"OK"}` ack body from the runtime invocation endpoints ([#1418](https://github.com/floci-io/floci/pull/1418))
- **ec2:** enable embedded DNS for instances ([#1390](https://github.com/floci-io/floci/pull/1390)); persist EC2 state so CloudFormation references survive a restart ([#1364](https://github.com/floci-io/floci/pull/1364))
- **cognito:** align the password recovery flow with AWS behavior ([#1415](https://github.com/floci-io/floci/pull/1415)); add missing `UserPoolClient` validation ([#1394](https://github.com/floci-io/floci/pull/1394))
- **autoscaling:** validate the `MixedInstancesPolicy` launch template ([#1439](https://github.com/floci-io/floci/pull/1439))
- **sqs:** process messages from a non-default account ([#1406](https://github.com/floci-io/floci/pull/1406))
- **ecs:** reject a negative `desiredCount` in `CreateService` and `UpdateService` ([#1409](https://github.com/floci-io/floci/pull/1409))
- **auth/s3:** resolve account context from the `X-Amz-Credential` query param in presigned URLs ([#1413](https://github.com/floci-io/floci/pull/1413))
- **apigateway:** support SQS query-protocol integrations (path-style URI) ([#1385](https://github.com/floci-io/floci/pull/1385))
- **rds:** report DB parameter group attachments ([#1392](https://github.com/floci-io/floci/pull/1392))
- **rds-data:** add native MySQL JDBC support ([#1352](https://github.com/floci-io/floci/pull/1352))
- **secretsmanager:** honor `ListSecrets` `MaxResults` and `NextToken` pagination ([#1383](https://github.com/floci-io/floci/pull/1383))
- **kms:** wrap RSA DIGEST signatures in a PKCS#1 `DigestInfo` ([#1396](https://github.com/floci-io/floci/pull/1396))
- **elasticache:** register `Endpoint` for reflection so HybridStorage can persist state ([#1403](https://github.com/floci-io/floci/pull/1403))
- **cloudwatch:** parse decimal epoch timestamps in the JSON protocol handler ([#1388](https://github.com/floci-io/floci/pull/1388))
- **ses:** canonicalize only the domain of suppression-list email addresses ([#1350](https://github.com/floci-io/floci/pull/1350))
- **scheduler:** support aws-sdk universal targets and FIFO `MessageGroupId` ([#1344](https://github.com/floci-io/floci/pull/1344))
- **config:** make runtime defaults explicit ([#1389](https://github.com/floci-io/floci/pull/1389))
- **core:** return an XML error for a missing action and add volume action fallbacks ([#1348](https://github.com/floci-io/floci/pull/1348))

### Changed

- **floci:** regroup the UI and DuckDB under `services.floci` ([#1397](https://github.com/floci-io/floci/pull/1397))
- **ci:** run focused native compatibility tests ([#1375](https://github.com/floci-io/floci/pull/1375))

### Documentation

- **cloudformation:** sync supported resource types and annotate stub actions ([#1405](https://github.com/floci-io/floci/pull/1405))
- **docdb:** document the DocumentDB service ([#1386](https://github.com/floci-io/floci/pull/1386))
- correct the service count to 58 and add EMR, WAF v2, and CloudTrail pages ([#1398](https://github.com/floci-io/floci/pull/1398))
- move brand assets into `docs/assets` and fix README image paths ([#1363](https://github.com/floci-io/floci/pull/1363))

## [1.5.25] - 2026-06-15

### Added

- **batch:** AWS Batch service support ([#1332](https://github.com/floci-io/floci/pull/1332))
- **wafv2:** AWS WAF v2 management API (Phase 1) ([#1327](https://github.com/floci-io/floci/pull/1327))
- **emr:** Amazon EMR management API (Phase 1) ([#1321](https://github.com/floci-io/floci/pull/1321))
- **rds-data:** RDS Data API support ([#1262](https://github.com/floci-io/floci/pull/1262))
- **rds:** provisioning support ([#1253](https://github.com/floci-io/floci/pull/1253))
- **cloudformation:** provision Lambda-backed custom resources and layer versions ([#1146](https://github.com/floci-io/floci/pull/1146))
- **glue:** support partition APIs ([#1315](https://github.com/floci-io/floci/pull/1315)); support column statistics ([#1309](https://github.com/floci-io/floci/pull/1309)); support table statistics deletion ([#1324](https://github.com/floci-io/floci/pull/1324))

### Fixed

- **cloudformation:** provision `AWS::EC2::VPC`/`Subnet` (and related networking) resources instead of stubbing, so exported subnet ids resolve in EC2 and ELBv2 ([#1342](https://github.com/floci-io/floci/pull/1342))
- **sqs:** raise the max message size to 1 MiB ([#1339](https://github.com/floci-io/floci/pull/1339)); release in-flight `ReceiveMessage` long polls when their queue is deleted ([#1286](https://github.com/floci-io/floci/pull/1286)); report `ApproximateNumberOfMessagesDelayed` in `GetQueueAttributes` ([#1285](https://github.com/floci-io/floci/pull/1285))
- **ses:** omit unknown identities from `GetIdentityNotificationAttributes` ([#1338](https://github.com/floci-io/floci/pull/1338)); align `MailFromAttributes` presence with MAIL FROM configuration ([#1310](https://github.com/floci-io/floci/pull/1310), [#1273](https://github.com/floci-io/floci/pull/1273)); persist inline options on v2 `CreateConfigurationSet` ([#1307](https://github.com/floci-io/floci/pull/1307))
- **glue:** return partitions in a deterministic order from `GetPartitions` ([#1335](https://github.com/floci-io/floci/pull/1335)); reject deletes of missing tables ([#1323](https://github.com/floci-io/floci/pull/1323))
- **sts:** persist the session secret key so RDS/ElastiCache IAM tokens validate ([#1266](https://github.com/floci-io/floci/pull/1266))
- **s3:** make conditional puts atomic ([#1329](https://github.com/floci-io/floci/pull/1329)); return `BadRequest` for dot-dot object keys ([#1325](https://github.com/floci-io/floci/pull/1325))
- **elbv2:** enforce ALB subnet availability-zone rules ([#1247](https://github.com/floci-io/floci/pull/1247)); improve target resolution and persistence ([#1254](https://github.com/floci-io/floci/pull/1254))
- **ec2:** embed the image catalog in the native image and load it lazily ([#1308](https://github.com/floci-io/floci/pull/1308))
- **ecs:** validate network mode and resources for Fargate task definitions ([#1274](https://github.com/floci-io/floci/pull/1274))
- **cognito:** include `USERNAME` in the SRP `PASSWORD_VERIFIER` challenge ([#1316](https://github.com/floci-io/floci/pull/1316))
- **appconfig:** stop AppConfig from hijacking the S3 bucket named `configuration` ([#1317](https://github.com/floci-io/floci/pull/1317))
- **athena:** align `GetWorkGroup` with AWS behavior ([#1301](https://github.com/floci-io/floci/pull/1301))
- **msk:** populate `currentBrokerSoftwareInfo` in the cluster describe response ([#1238](https://github.com/floci-io/floci/pull/1238))
- **parity:** pre-create LocalStack/Floci init-hook directories ([#1337](https://github.com/floci-io/floci/pull/1337))

### Documentation

- **lambda:** document Podman rootless support and the Runtime API host override ([#1340](https://github.com/floci-io/floci/pull/1340))

## [1.5.24] - 2026-06-11

### Added

- **cloudtrail:** trail lifecycle support ([#1256](https://github.com/floci-io/floci/pull/1256))
- **cloudmap:** AWS Cloud Map (servicediscovery) management API ([#1217](https://github.com/floci-io/floci/pull/1217))
- **ec2:** provisioning primitives — launch templates, VPC endpoints, NAT gateways, instance type offerings ([#1252](https://github.com/floci-io/floci/pull/1252))
- **ec2:** auto-create default security group and main route table on `CreateVpc` ([#1177](https://github.com/floci-io/floci/pull/1177))
- **eks:** support managed node groups ([#1226](https://github.com/floci-io/floci/pull/1226))
- **kms:** key state and description operations (`DisableKey`, `UpdateKeyDescription`) ([#1251](https://github.com/floci-io/floci/pull/1251))
- **cognito:** verification code subsystem ([#1249](https://github.com/floci-io/floci/pull/1249))
- **athena:** support `DeleteWorkGroup` ([#1213](https://github.com/floci-io/floci/pull/1213)); align `CreateWorkGroup` with AWS ([#1174](https://github.com/floci-io/floci/pull/1174))
- **glue:** register database tags with the Resource Groups Tagging API ([#1248](https://github.com/floci-io/floci/pull/1248)); support `UpdateDatabase` ([#1232](https://github.com/floci-io/floci/pull/1232)); archive table versions and return them from `GetTableVersions` ([#1233](https://github.com/floci-io/floci/pull/1233))
- **elbv2:** align `DescribeLoadBalancers` availability zones with AWS behavior ([#1206](https://github.com/floci-io/floci/pull/1206))
- **ses:** publish events to Firehose, EventBridge, and CloudWatch ([#1188](https://github.com/floci-io/floci/pull/1188))
- **cloudformation:** provision `AWS::StepFunctions::StateMachine` ([#1152](https://github.com/floci-io/floci/pull/1152))
- **s3:** stream range responses ([#1255](https://github.com/floci-io/floci/pull/1255))
- **parity:** emit LocalStack-compatible `Ready.` log line on startup ([#1284](https://github.com/floci-io/floci/pull/1284))

### Fixed

- **athena:** align `ListWorkGroups` with AWS behavior ([#1259](https://github.com/floci-io/floci/pull/1259))
- **elasticache:** return reachable Memcached configuration endpoints ([#1258](https://github.com/floci-io/floci/pull/1258))
- **elbv2:** validate subnet VPC consistency for load balancers ([#1244](https://github.com/floci-io/floci/pull/1244))
- **msk:** poll the correct Redpanda admin readiness endpoint ([#1237](https://github.com/floci-io/floci/pull/1237))
- **lambda:** normalize backslash ZIP path separators on extraction ([#1215](https://github.com/floci-io/floci/pull/1215))
- **s3:** reject relative object keys ([#1235](https://github.com/floci-io/floci/pull/1235))
- **glue:** preserve column parameters ([#1210](https://github.com/floci-io/floci/pull/1210)); support `BatchDeleteTable` ([#1209](https://github.com/floci-io/floci/pull/1209)); normalize catalog names ([#1208](https://github.com/floci-io/floci/pull/1208))
- **docker:** fall back to the default command when entrypoint argv is empty ([#1283](https://github.com/floci-io/floci/pull/1283))
- **cloudmap:** avoid `SecureRandom` in the native image heap ([#1271](https://github.com/floci-io/floci/pull/1271), [#1289](https://github.com/floci-io/floci/pull/1289))

### Documentation

- adopt the AGENTS.md standard and update references ([#1225](https://github.com/floci-io/floci/pull/1225), [#1272](https://github.com/floci-io/floci/pull/1272))
- **cloudmap:** add service docs ([#1221](https://github.com/floci-io/floci/pull/1221))

## [1.5.23] - 2026-06-07

### Added

- **iam:** seed the standard EKS cluster and node-group managed policies ([#1216](https://github.com/floci-io/floci/pull/1216))
- **appsync:** Phase 2 — schema registry, model fixes, CRUDL completion, and AWS scalars ([#1185](https://github.com/floci-io/floci/pull/1185))
- **cloudwatch:** accept a log group ARN via `logGroupIdentifier` ([#1172](https://github.com/floci-io/floci/pull/1172))
- **ecs:** honour task-definition volumes and container `mountPoints` ([#1171](https://github.com/floci-io/floci/pull/1171))
- **ses:** per-configuration-set sending toggle (v1 + v2) ([#1193](https://github.com/floci-io/floci/pull/1193))
- **glue:** support `DeleteDatabase` ([#1181](https://github.com/floci-io/floci/pull/1181))
- **glue:** support user-defined functions ([#1187](https://github.com/floci-io/floci/pull/1187))

### Fixed

- **glue:** enforce table version checks ([#1184](https://github.com/floci-io/floci/pull/1184), [#1211](https://github.com/floci-io/floci/pull/1211))
- **glue:** preserve view table fields ([#1182](https://github.com/floci-io/floci/pull/1182))
- **dynamodb:** persist the SSE specification and return `SSEDescription` for encrypted tables ([#1191](https://github.com/floci-io/floci/pull/1191))
- **dynamodb:** accept `AttributesToGet` with `Select=SPECIFIC_ATTRIBUTES` in `Query` ([#1190](https://github.com/floci-io/floci/pull/1190))
- **dynamodb:** update the reserved keywords list ([#1176](https://github.com/floci-io/floci/pull/1176))
- **ecs:** retain inactive services for Terraform draining and recreation with correct idempotency ([#1166](https://github.com/floci-io/floci/pull/1166))
- **ec2:** include `AttachTime` in the describe-instances network interface response ([#1214](https://github.com/floci-io/floci/pull/1214))
- **stepfunctions:** honor `Catch` on Lambda task failures ([#1196](https://github.com/floci-io/floci/pull/1196))
- **cloudformation:** poll for `CREATE_COMPLETE` in the SAM changeset test ([#1199](https://github.com/floci-io/floci/pull/1199))
- **sns:** align mobile push error codes, payload resolution, and Token rotation with AWS ([#1165](https://github.com/floci-io/floci/pull/1165))
- **elbv2:** align `DeleteTargetGroup` with the AWS Query response ([#1194](https://github.com/floci-io/floci/pull/1194))
- **cloudwatch:** tag rpc-v2-cbor timestamp lists so `GetMetricData` decodes ([#1162](https://github.com/floci-io/floci/pull/1162))

### Documentation

- **ses:** list the v1 `ConfigurationSetEventDestination` actions ([#1192](https://github.com/floci-io/floci/pull/1192))

### Build

- bump Quarkus to 3.36.0 and patch build dependencies ([#1197](https://github.com/floci-io/floci/pull/1197))

## [1.5.22] - 2026-06-04

### Added

- **eks:** make real-mode clusters reachable from the host and authenticate `aws eks get-token` via a token webhook ([#1167](https://github.com/floci-io/floci/pull/1167))
- **ecs:** honour RunTask `containerOverrides` — per-container `command` and `environment` ([#1151](https://github.com/floci-io/floci/pull/1151))
- **ecs:** provision ECS resources via CloudFormation ([#1120](https://github.com/floci-io/floci/pull/1120))
- **elbv2:** provision ELBv2 resources via CloudFormation ([#1121](https://github.com/floci-io/floci/pull/1121))
- **cloudformation:** persist stacks across restart ([#1124](https://github.com/floci-io/floci/pull/1124))
- **sns:** mock mobile push for iOS and Android platform endpoints ([#1115](https://github.com/floci-io/floci/pull/1115))
- **kms:** `GenerateRandom` action ([#1036](https://github.com/floci-io/floci/pull/1036))
- **appsync:** AWS AppSync management API (Phase 1) ([#1108](https://github.com/floci-io/floci/pull/1108))
- **ses:** publish SES events to SNS configuration set destinations ([#1106](https://github.com/floci-io/floci/pull/1106))
- **ses:** V1 `ConfigurationSetEventDestination` CRUD ([#1111](https://github.com/floci-io/floci/pull/1111))
- **ses:** apply the suppression list during send, with a per-configuration-set `SuppressionOptions` override ([#1109](https://github.com/floci-io/floci/pull/1109))

### Fixed

- **eventbridge:** propagate the `PutEvents` call region and account through pattern matching and the delivered envelope ([#1125](https://github.com/floci-io/floci/pull/1125))
- **cloudformation:** roll back failed stack creates ([#1123](https://github.com/floci-io/floci/pull/1123))
- **apigatewayv2:** support the CloudFormation update path ([#1122](https://github.com/floci-io/floci/pull/1122))
- **apigateway:** implement `EndpointConfiguration` for REST APIs ([#1116](https://github.com/floci-io/floci/pull/1116))
- **athena:** align metadata behavior with AWS ([#1119](https://github.com/floci-io/floci/pull/1119))
- **lambda:** support path-style ECR URIs for image functions ([#1135](https://github.com/floci-io/floci/pull/1135))
- **elb:** return `TargetGroupNotFound` for missing target groups ([#1161](https://github.com/floci-io/floci/pull/1161))
- **ecs:** preserve create-time resource tags ([#1139](https://github.com/floci-io/floci/pull/1139))
- **ecr:** resolve the backing registry endpoint from the control plane ([#1113](https://github.com/floci-io/floci/pull/1113))
- **docker:** retry transient registry failures during image pull ([#1117](https://github.com/floci-io/floci/pull/1117))
- **dns:** resolve public hostnames from spawned containers ([#1168](https://github.com/floci-io/floci/pull/1168))
- **s3:** handle empty object suffix ranges ([#1142](https://github.com/floci-io/floci/pull/1142))
- **s3:** return an error on unsupported S3 Select expressions ([#1160](https://github.com/floci-io/floci/pull/1160))
- **core:** use the request region consistently across service operations ([#1105](https://github.com/floci-io/floci/pull/1105))

### Security

- **s3:** enforce SSE-C customer keys on object reads, writes, copies, and multipart uploads ([#1143](https://github.com/floci-io/floci/pull/1143))

### Documentation

- **appsync:** add AppSync service documentation ([#1149](https://github.com/floci-io/floci/pull/1149))
- add light/dark logo branding, mkdocs theming, and README badges ([#1150](https://github.com/floci-io/floci/pull/1150))

### Build

- bump `actions/checkout` from 4 to 6.0.2 ([#1159](https://github.com/floci-io/floci/pull/1159))
- bump `aws-actions/configure-aws-credentials` from 4 to 6.1.2 ([#1158](https://github.com/floci-io/floci/pull/1158))
- bump the actions-minor-patch group with 5 updates ([#1157](https://github.com/floci-io/floci/pull/1157))

## [1.5.21] - 2026-05-31

### Added

- **ses:** SES V2 configuration set event destination operations ([#1079](https://github.com/floci-io/floci/pull/1079))
- **firehose:** `TagDeliveryStream`, `UntagDeliveryStream`, and `ListTagsForDeliveryStream` ([#1061](https://github.com/floci-io/floci/pull/1061))
- **cognito:** custom schema attributes and attribute deletion APIs ([#1060](https://github.com/floci-io/floci/pull/1060))
- **cognito:** OAuth2 `/oauth2/userInfo` endpoint ([#1062](https://github.com/floci-io/floci/pull/1062))
- **kms:** handle `RotateKeyOnDemand` rotation limit ([#1042](https://github.com/floci-io/floci/pull/1042))
- **ec2:** required methods for VPC, subnet, and instance operations ([#959](https://github.com/floci-io/floci/pull/959))

### Fixed

- **ec2:** volume throughput, `DescribeAddressesAttribute`, and IAM instance profile parity ([#1103](https://github.com/floci-io/floci/pull/1103))
- **ec2:** add missing subnet attributes and instance block device `attachTime` ([#1087](https://github.com/floci-io/floci/pull/1087))
- **dynamodb:** merge nested map paths in `ProjectionExpression` ([#1099](https://github.com/floci-io/floci/pull/1099))
- **s3:** reject `response-*` overrides on unsigned requests ([#1098](https://github.com/floci-io/floci/pull/1098))
- **eventbridge:** tag rules on custom buses whose name contains "event-bus" ([#1102](https://github.com/floci-io/floci/pull/1102))
- **apigateway:** implement V1 `DeleteDeployment` endpoint and return correct JSON 404 error ([#1059](https://github.com/floci-io/floci/pull/1059))
- **cloudformation:** forward ApiGatewayV2 `Api` properties to the apigatewayv2 service ([#1086](https://github.com/floci-io/floci/pull/1086))
- **rds:** restore persisted runtime on startup ([#1071](https://github.com/floci-io/floci/pull/1071))
- **logging:** render port numbers without locale grouping separators ([#1104](https://github.com/floci-io/floci/pull/1104))

### Build

- move all builds to GraalVM 25 ([#1107](https://github.com/floci-io/floci/pull/1107))
- enforce JDK 25 requirement with Maven Enforcer Plugin ([#1101](https://github.com/floci-io/floci/pull/1101))

## [1.5.20] - 2026-05-29

### Added

- **cors:** global CORS filter for browser-based local development — allows `OPTIONS` preflight and injects CORS headers on all responses ([#849](https://github.com/floci-io/floci/pull/849))
- **kms:** `CreateGrant`, `RetireGrant`, `RevokeGrant`, `ListGrants`, `ListRetirableGrants`, and `DescribeKey` grant enumeration ([#1073](https://github.com/floci-io/floci/pull/1073))
- **opensearch:** round-trip VPC config, security options, encryption-at-rest, node-to-node encryption, and per-family instance type metadata ([#1039](https://github.com/floci-io/floci/pull/1039))
- **opensearch:** support OpenSearch 3.x, validate engine versions, use real ES 7.10 OSS image ([#1025](https://github.com/floci-io/floci/pull/1025))
- **cloudformation:** SAM Transform support — `AWS::Serverless-2016-10-31` templates are now transformed before stack deployment ([#1012](https://github.com/floci-io/floci/pull/1012))

### Fixed

- **ecs:** reconcile task lifecycle when container exits naturally — tasks reach `STOPPED` state correctly without manual intervention ([#1078](https://github.com/floci-io/floci/pull/1078))
- **s3:** clean up versioned file data on permanent version deletion to prevent stale data accumulation ([#1045](https://github.com/floci-io/floci/pull/1045))
- **apigateway:** resolve APIs created outside the default region so cross-region requests are routed correctly ([#1032](https://github.com/floci-io/floci/pull/1032))
- **eventbridge:** enforce `$or` negation in event pattern matching — negative `$or` conditions now correctly exclude matching events ([#1068](https://github.com/floci-io/floci/pull/1068))
- **apigw-v2:** populate `pathParameters` in Lambda proxy event for HTTP API integrations ([#1067](https://github.com/floci-io/floci/pull/1067))
- **ec2:** skip terminated instances in `DescribeNetworkInterfaces` ([#1074](https://github.com/floci-io/floci/pull/1074))
- **ec2:** return `volumeId` in `BlockDeviceMapping` and `disableApiStop`/`disableApiTermination` in `DescribeInstanceAttribute` ([#1028](https://github.com/floci-io/floci/pull/1028))
- **ec2:** fix `DescribeNetworkInterfaces` response shape — missing fields and incorrect filtering ([#1035](https://github.com/floci-io/floci/pull/1035))
- **ecr:** shut down registry manager on observable shutdown event to prevent port leaks ([#1063](https://github.com/floci-io/floci/pull/1063))
- **s3:** omit whole-object checksum headers on range (`GET` with `Range:`) responses ([#1040](https://github.com/floci-io/floci/pull/1040))
- **s3:** apply `TaggingDirective=COPY` default in `CopyObject` — tags are now copied when directive is omitted ([#1049](https://github.com/floci-io/floci/pull/1049))
- **elbv2:** offload Lambda invocation to a worker thread to avoid blocking the event loop ([#1034](https://github.com/floci-io/floci/pull/1034))
- **cloudformation:** implement nested stack deployment via `AWS::CloudFormation::Stack` ([#1052](https://github.com/floci-io/floci/pull/1052))
- **cloudformation:** retain deleted stacks briefly for destroy-polling so SDKs can observe `DELETE_COMPLETE` status ([#1029](https://github.com/floci-io/floci/pull/1029))
- **lambda:** fix WarmPool timeout when stopping multiple containers — containers now shut down in parallel ([#1055](https://github.com/floci-io/floci/pull/1055))

### Documentation

- **neptune:** add Neptune service page and fix missing index entries ([#1056](https://github.com/floci-io/floci/pull/1056))

## [1.5.19] - 2026-05-24

### Added

- **lambda:** Lambda layer support — `PublishLayerVersion`, `GetLayerVersion`, `ListLayerVersions`, `DeleteLayerVersion` ([#1015](https://github.com/floci-io/floci/pull/1015))
- **elbv2:** `DescribeListenerAttributes`, `ModifyListenerAttributes`, `DescribeCapacityReservation`, and `ModifyCapacityReservation` actions ([#1021](https://github.com/floci-io/floci/pull/1021))
- **kms:** `RotateKeyOnDemand` support for on-demand key rotation ([#990](https://github.com/floci-io/floci/pull/990))
- **apigateway:** Lambda REQUEST authorizer support for HTTP API v2 routes ([#1011](https://github.com/floci-io/floci/pull/1011))
- **apigateway:** persist `UpdateStage` `methodSettings` patch operations on v1 stages ([#1004](https://github.com/floci-io/floci/pull/1004))
- **cognito:** `UpdateGroup`, `ListUsersInGroup`, and `AdminConfirmSignUp` actions ([#998](https://github.com/floci-io/floci/pull/998))
- **ci:** Docker images now published to ECR Public Gallery ([#1000](https://github.com/floci-io/floci/pull/1000))

### Fixed

- **s3:** remove private `hasQueryParam` in `S3Controller` that shadowed the static import from `S3RequestParser` — presigned `PUT` requests with `x-amz-tagging` in `X-Amz-SignedHeaders` were misrouted to `PutObjectTagging` and returned 404 ([#1023](https://github.com/floci-io/floci/pull/1023))
- **iam:** honour 12-digit access key as account ID in IAM ARNs — `CreateRole`, `CreateUser`, `CreateGroup`, `CreatePolicy`, and `CreateInstanceProfile` always returned `000000000000` regardless of caller identity ([#1017](https://github.com/floci-io/floci/pull/1017))
- **s3:** persist inline `x-amz-tagging` header on `PutObject` ([#987](https://github.com/floci-io/floci/pull/987))
- **apigateway:** implement `DeleteDeployment` endpoint and return correct JSON 404 error for missing resources ([#1019](https://github.com/floci-io/floci/pull/1019))
- **rds:** register `DbEndpoint` for native-image reflection ([#1014](https://github.com/floci-io/floci/pull/1014))
- **apigateway:** populate full `APIGatewayRequestAuthorizerEvent` fields for REQUEST authorizers ([#1002](https://github.com/floci-io/floci/pull/1002))
- **sns:** return per-entry failures from `PublishBatch` instead of aborting the whole batch on a single entry failure ([#1008](https://github.com/floci-io/floci/pull/1008))
- **cognito:** CloudFormation provisioning for `UserPool` and `UserPoolClient` resource types ([#1007](https://github.com/floci-io/floci/pull/1007))
- **lifecycle:** fix incorrect readiness status display when TLS is enabled and AppConfig is initialising ([#1006](https://github.com/floci-io/floci/pull/1006))
- **s3:** unblock `.well-known/*` object keys that were hijacked by Cognito OIDC routes ([#1003](https://github.com/floci-io/floci/pull/1003))
- **sns:** preserve binary message attributes through `Publish` and fan-out delivery ([#989](https://github.com/floci-io/floci/pull/989))
- **cognito:** include user attributes in ID token claims ([#985](https://github.com/floci-io/floci/pull/985))
- **ecr:** release registry port when container start fails to prevent port exhaustion ([#1010](https://github.com/floci-io/floci/pull/1010))
- **s3:** restore object data on delete marker removal and return missing version headers ([#966](https://github.com/floci-io/floci/pull/966))
- **sts:** honour 12-digit access key as account ID in STS responses ([#983](https://github.com/floci-io/floci/pull/983))
- **sqs:** accept bare queue name in `QueueUrl` field ([#980](https://github.com/floci-io/floci/pull/980))
- **s3:** accept region-qualified virtual-host form `bucket.s3.<region>.<host>` ([#978](https://github.com/floci-io/floci/pull/978))
- **sqs/sns:** add `ContentBasedDeduplication` support and SNS subscription CloudFormation provisioning ([#974](https://github.com/floci-io/floci/pull/974))
- **s3:** handle `PutBucketRequestPayment` instead of falling through to `CreateBucket` ([#994](https://github.com/floci-io/floci/pull/994))

### Documentation

- **pipes:** add documentation and navigation for EventBridge Pipes ([#1001](https://github.com/floci-io/floci/pull/1001))
- **tagging:** add Resource Groups Tagging service documentation ([#976](https://github.com/floci-io/floci/pull/976))
- **transcribe:** add Transcribe service documentation ([#975](https://github.com/floci-io/floci/pull/975))

## [1.5.18] - 2026-05-21

### Added

- **cloudfront:** CloudFront service emulation — `CreateDistribution`, `GetDistribution`, `UpdateDistribution`, `DeleteDistribution`, `ListDistributions`; distribution state, origins, cache behaviors, and viewer certificate management ([#969](https://github.com/floci-io/floci/pull/969))
- **config:** AWS Config service emulation — configuration recorders, delivery channels, config rules, `StartConfigurationRecorder`, `StopConfigurationRecorder`, `DescribeConfigurationRecorders`, `PutDeliveryChannel`, `DescribeDeliveryChannels`, `PutConfigRule`, `DescribeConfigRules`, `DeleteConfigRule` ([#934](https://github.com/floci-io/floci/pull/934))
- **neptune:** SDK compatibility tests added to the Neptune control plane and Gremlin Docker backend ([#958](https://github.com/floci-io/floci/pull/958))
- **apigatewayv2:** HTTP API requests are now forwarded through ALB listeners — HTTP_PROXY integrations targeting ALB are resolved at dispatch time, enabling ECS/ELBv2 stacks backed by API Gateway HTTP APIs ([#941](https://github.com/floci-io/floci/pull/941))
- **sqs:** persist and return `AWSTraceHeader` system attribute on `SendMessage` and `ReceiveMessage` for X-Ray tracing compatibility ([#909](https://github.com/floci-io/floci/pull/909))
- **sqs:** implement `StartMessageMoveTask`, `ListMessageMoveTasks`, and `CancelMessageMoveTask` with async drain and configurable `MaxNumberOfMessagesPerSecond` rate limiting ([#910](https://github.com/floci-io/floci/pull/910))
- **kms:** implement `GenerateMac` and `VerifyMac` for HMAC keys (`HMAC_256`, `HMAC_384`, `HMAC_512`) ([#927](https://github.com/floci-io/floci/pull/927))
- **apigateway:** handle inbound requests to custom domain names — custom domain name mappings are matched before stage routing, enabling SDK and Terraform workflows that create `AWS::ApiGateway::DomainName` resources ([#922](https://github.com/floci-io/floci/pull/922))
- **ecs:** register ECS service containers as ELBv2 targets — ECS tasks are automatically registered and deregistered in target groups when services scale or update ([#929](https://github.com/floci-io/floci/pull/929))
- **elasticache:** Memcached cluster management — `CreateCacheCluster` and `DescribeCacheClusters` for `memcached` engine; Memcached data-plane proxy backed by a real Memcached Docker container ([#930](https://github.com/floci-io/floci/pull/930))
- **sns:** HTTP/HTTPS endpoint delivery — subscriptions with `http` or `https` protocol now deliver notifications via real HTTP POST with the standard SNS envelope; includes subscription confirmation handshake ([#865](https://github.com/floci-io/floci/pull/865))
- **secretsmanager:** add `RestoreSecret` operation — restores a soft-deleted secret, clears the `DeletedDate`, and returns the updated metadata ([#915](https://github.com/floci-io/floci/pull/915))

### Fixed

- **s3:** return `ObjectLockConfigurationNotFoundError` when `GetObjectLockConfiguration` is called on a bucket that was not created with Object Lock enabled ([#971](https://github.com/floci-io/floci/pull/971))
- **core:** lowercase `smithy-protocol` response header to `smithy-protocol` — some AWS SDK versions rejected the mixed-case variant, breaking CBOR responses ([#968](https://github.com/floci-io/floci/pull/968))
- **s3:** omit `Content-Type` body-description headers and user-defined metadata (`x-amz-meta-*`) from `PutObject` responses — these fields are request-only and their presence in the response caused SDK assertion failures ([#948](https://github.com/floci-io/floci/pull/948))
- **rds:** persist instance and cluster metadata across storage-backed restarts — `DescribeDBInstances` and `DescribeDBClusters` returned empty results after restart ([#945](https://github.com/floci-io/floci/pull/945))
- **s3:** fix `ListObjects` v1 `marker`-based pagination — the next-page marker was not advanced correctly, causing infinite loops or skipped objects on subsequent pages ([#939](https://github.com/floci-io/floci/pull/939))
- **sqs:** reject `SendMessageBatch` when the combined payload exceeds `MaximumMessageSize` — previously only individual message size was checked ([#907](https://github.com/floci-io/floci/pull/907))
- **apigatewayv2:** include `corsConfiguration` in HTTP API `Get`/`Create`/`Update` responses ([#933](https://github.com/floci-io/floci/pull/933))
- **core:** fix crash when the request `Content-Type` header is an empty string — previously threw a `NullPointerException` during media-type parsing ([#940](https://github.com/floci-io/floci/pull/940))
- **lambda:** log an actionable hint when the concurrency pool is exhausted instead of returning a bare 429 ([#935](https://github.com/floci-io/floci/pull/935))
- **dynamodb:** correct `DeleteTable` status transitions — table enters `DELETING` before removal; fix PartiQL `SELECT` scan to respect `FilterExpression` ([#915](https://github.com/floci-io/floci/pull/915))
- **lambda:** make `PortAllocator` a checkout/release pool — prevents port collisions under concurrent invocations and restores released ports to the free set ([#936](https://github.com/floci-io/floci/pull/936))
- **apigatewayv2:** raise Vert.x WebSocket frame size limit to 256 KB to match AWS WebSocket API frame limits ([#938](https://github.com/floci-io/floci/pull/938))
- **apigatewayv2:** include `connectionType` and `enableSimpleResponses` fields in `Integration` and `Authorizer` responses ([#931](https://github.com/floci-io/floci/pull/931))
- **sns:** preserve per-entry `MessageAttributes` in `PublishBatch` — attributes from sibling entries were leaking across batch items ([#891](https://github.com/floci-io/floci/pull/891))
- **lambda:** await `HttpServer` close in `RuntimeApiServer.stop()` to prevent in-flight `/next` polls from being dropped during graceful shutdown ([#926](https://github.com/floci-io/floci/pull/926))
- **apigateway:** accept any `Content-Type` on `PutRestApi` and `ImportRestApi` — previously rejected non-`application/json` bodies, breaking Swagger YAML imports ([#925](https://github.com/floci-io/floci/pull/925))
- **s3:** honor `response-content-type`, `response-content-disposition`, `response-cache-control`, and other `response-*` query parameters on `GetObject` and `HeadObject` ([#923](https://github.com/floci-io/floci/pull/923))
- **cognito:** correct `USER_SRP_AUTH` `PASSWORD_VERIFIER` challenge signature computation — HMAC-SHA256 was using the wrong derived key, causing authentication failures with real SRP clients ([#912](https://github.com/floci-io/floci/pull/912))
- **ec2:** add missing instance response fields (`privateDnsNameOptions`, `maintenanceOptions`, `currentInstanceBootMode`) to fix Terraform nil pointer dereference on `aws_instance` resources ([#883](https://github.com/floci-io/floci/pull/883))
- **sns:** emit canonical AWS error codes (`InvalidParameterValue`) for `Publish` and `PublishBatch` message size violations ([#906](https://github.com/floci-io/floci/pull/906))
- **sqs:** skip JSON `null` tag and attribute values instead of persisting the literal string `"null"` ([#905](https://github.com/floci-io/floci/pull/905))
- **sqs:** scope FIFO deduplication ID lookup per `MessageGroupId` when `DeduplicationScope=messageGroup` is set on the queue ([#908](https://github.com/floci-io/floci/pull/908))

### Documentation

- Update README with service coverage and configuration examples ([#885](https://github.com/floci-io/floci/pull/885))

## [1.5.17] - 2026-05-18

### Added

- **neptune:** Neptune control plane — `CreateDBCluster`, `DescribeDBClusters`, `ModifyDBCluster`, `DeleteDBCluster`, `CreateDBInstance`, `DescribeDBInstances`, `ModifyDBInstance`, `DeleteDBInstance`; each cluster is backed by a real TinkerPop Gremlin Server Docker container behind a transparent TCP proxy ([#881](https://github.com/floci-io/floci/pull/881))
- **apigateway:** `GetAccount` and `UpdateAccount` — account-level configuration including throttle settings and CloudWatch log role ARN; enables Terraform and CDK stacks that call `aws_api_gateway_account` ([#867](https://github.com/floci-io/floci/pull/867))
- **apigatewayv2:** `HTTP_PROXY` integration type — forwards inbound requests to the configured `IntegrationUri` with `RequestParameters` transformations matching AWS-faithful semantics ([#860](https://github.com/floci-io/floci/pull/860))
- **dynamodb:** `ExecuteStatement`, `ExecuteTransaction`, `BatchExecuteStatement` — hand-rolled PartiQL tokenizer and recursive-descent parser supporting SELECT/INSERT/UPDATE/DELETE, `?` positional placeholders, `begins_with`, and `BETWEEN`; translates to existing service calls with no new storage ([#864](https://github.com/floci-io/floci/pull/864))
- **sns:** `FilterPolicyScope=MessageBody` — filter policies scoped to the message body are now evaluated at publish time; previously the scope was persisted but ignored, delivering all messages regardless of the policy ([#863](https://github.com/floci-io/floci/pull/863))
- **cognito:** `PostConfirmation` and `PreSignUp` Lambda triggers — `PostConfirmation_ConfirmSignUp` fires after `ConfirmSignUp`; `PreSignUp_SignUp` fires before user persistence on `SignUp`; `autoConfirmUser=true` in the PreSignUp response auto-confirms and chains into PostConfirmation ([#859](https://github.com/floci-io/floci/pull/859))
- **ses:** `PutAccountSuppressionAttributes` (`PUT /v2/email/account/suppression`) — set account-level suppression list reasons (`BOUNCE`/`COMPLAINT`); `GetAccount` response now includes a `SuppressionAttributes` object reflecting the stored setting ([#853](https://github.com/floci-io/floci/pull/853))
- **cur / bcm-data-exports:** Cost and Usage Reports (`AWSOrigamiServiceGatewayService.*`) and BCM Data Exports (`AWSBillingAndCostManagementDataExports.*`) management planes sharing one underlying export pipeline ([#850](https://github.com/floci-io/floci/pull/850))

### Fixed

- **dynamodb:** honor legacy `Expected` condition in `UpdateItem` — `handleUpdateItem` now reads the `Expected` field, and `evaluateLegacyExpected` normalizes the legacy `{"Value": x}` shape to `{"AttributeValueList": [x]}` before evaluation; conditional writes that should fail were silently succeeding ([#888](https://github.com/floci-io/floci/pull/888))
- **apigatewayv2:** `Route` responses now include `authorizerId` — the field was persisted and used at dispatch time but never emitted by `toV2RouteNode`, breaking SDK and Terraform reads ([#887](https://github.com/floci-io/floci/pull/887))
- **eventbridge:** persisted rules no longer disappear on reload — `Rule.getRegion()` is a derived getter with no setter; `ObjectMapper` wrote `"region"` into `eventbridge-rules.json` and then threw `UnrecognizedPropertyException` on startup, causing all persisted rules to be silently dropped ([#878](https://github.com/floci-io/floci/pull/878))
- **s3:** `PutObject` now returns the checksum for the algorithm actually specified by the caller; previously always returned `ChecksumSHA1` + `ChecksumSHA256` regardless of `--checksum-algorithm` ([#877](https://github.com/floci-io/floci/pull/877))
- **eks:** cluster readiness polling now uses the container's bridge IP instead of the container hostname, fixing clusters stuck in `CREATING` when Floci runs inside Docker; startup failures are handled gracefully instead of returning 500 ([#873](https://github.com/floci-io/floci/pull/873))
- **eventbridge:** region is now correctly propagated through rule dispatch so SQS queue lookup uses the rule's region rather than the primary region ([#870](https://github.com/floci-io/floci/pull/870))
- **eventbridge:** `EventBusName` is now accepted as a full ARN (`arn:aws:events:…:event-bus/my-bus`) in addition to a plain name across all EventBridge operations ([#843](https://github.com/floci-io/floci/pull/843))

## [1.5.16] - 2026-05-15

### Added

- **stepfunctions:** `ValidateStateMachineDefinition` — validates ASL definitions and returns structured diagnostics with `/States/<state>/<field>` paths for precise error location ([#845](https://github.com/floci-io/floci/pull/845))
- **stepfunctions:** `ListTagsForResource`, `TagResource`, `UntagResource` for state machines and activities; covers both the AWS SDK (JSON 1.0) and Terraform (REST) wire paths ([#767](https://github.com/floci-io/floci/pull/767))
- **rds:** cluster parameter group actions — `CreateDBClusterParameterGroup`, `DescribeDBClusterParameterGroups`, `DeleteDBClusterParameterGroup`, `ModifyDBClusterParameterGroup`, `DescribeDBClusterParameters`; mirrors the existing instance-level parameter group shape and unblocks IaC stacks that define Aurora `ClusterParameterGroup` resources ([#838](https://github.com/floci-io/floci/pull/838))
- **s3:** full `SelectObjectContent` evaluator with floci-duck integration — recursive WHERE (AND, OR, NOT, LIKE, BETWEEN, IN, IS NULL, comparisons), JSON Lines and JSON array input formats, cross-format output (CSV→JSON, JSON→CSV), real `BytesScanned`/`BytesReturned` stats; routes CSV and JSON through floci-duck when available with a pure-Java fallback for standalone mode ([#835](https://github.com/floci-io/floci/pull/835))
- **eventbridge:** `UpdateEventBus` — update event bus description, KMS key, dead-letter config, or log config ([#834](https://github.com/floci-io/floci/pull/834))
- **ce:** AWS Cost Explorer service — `GetCostAndUsage`, `GetCostAndUsageWithResources`, `GetDimensionValues`, `GetTags`, `GetReservationCoverage`, `GetReservationUtilization`, `GetSavingsPlansCoverage`, `GetSavingsPlansUtilization`, `GetCostCategories`; cost is synthesized from existing resource state multiplied by the bundled pricing snapshot; supports `DAILY`/`MONTHLY`/`HOURLY` granularity, all standard metrics, and `GroupBy` on `DIMENSION`, `TAG`, and `COST_CATEGORY` ([#832](https://github.com/floci-io/floci/pull/832))
- **transcribe:** AWS Transcribe service — `StartTranscriptionJob`, `GetTranscriptionJob`, `ListTranscriptionJobs`, `DeleteTranscriptionJob`, `CreateVocabulary`, `GetVocabulary`, `ListVocabularies`, `UpdateVocabulary`, `DeleteVocabulary` ([#827](https://github.com/floci-io/floci/pull/827))

### Fixed

- **core:** remove AWS SDK from Floci's runtime classpath to eliminate version conflicts with user-bundled SDKs ([#846](https://github.com/floci-io/floci/pull/846))
- **core:** improve log level structure and reduce noise in hot paths ([#847](https://github.com/floci-io/floci/pull/847))
- **s3:** make `us-east-1` bucket creation idempotent — repeated `CreateBucket` calls for the same bucket in `us-east-1` no longer raise `BucketAlreadyOwnedByYou` ([#844](https://github.com/floci-io/floci/pull/844))
- **ec2:** support wildcard (`*`, `?`) filter values across all EC2 resources (VPCs, Subnets, Security Groups, Instances, Internet Gateways, Route Tables, Volumes), matching real AWS filter behavior ([#837](https://github.com/floci-io/floci/pull/837))
- **pricing:** bundle pricing snapshot resources into the native image — the Price List service was unavailable in GraalVM native builds ([#836](https://github.com/floci-io/floci/pull/836))
- **kms:** `Encrypt` now derives a fresh AES-GCM nonce per call (was deterministic, leaking plaintext equality); `Decrypt` now validates `EncryptionContext` as AAD and raises `InvalidCiphertextException` on any mismatch ([#825](https://github.com/floci-io/floci/pull/825))
- **s3:** strip `charset` parameter from `Content-Type` in XML responses ([#809](https://github.com/floci-io/floci/pull/809))
- **sqs:** FIFO `ReceiveMessage` now returns messages from multiple distinct message groups in a single call ([#785](https://github.com/floci-io/floci/pull/785))
- **sqs:** honor `AttributeNames` and `MessageSystemAttributeNames` on `ReceiveMessage` ([#784](https://github.com/floci-io/floci/pull/784))
- **sqs:** implement `AddPermission` and `RemovePermission` ([#781](https://github.com/floci-io/floci/pull/781))
- **stepfunctions:** honor `TimeoutSeconds` on `Parallel` state ([#771](https://github.com/floci-io/floci/pull/771))

### Documentation

- Improve README readability ([#831](https://github.com/floci-io/floci/pull/831))

## [1.5.15] - 2026-05-13

### Added

- **pricing:** AWS Price List Service — `DescribeServices`, `GetAttributeValues`, `GetProducts`, `ListPriceLists`, `GetPriceListFileUrl` with pagination; backed by a bundled static snapshot; `PriceList` shape matches the real SDK's array-of-JSON-strings format ([#821](https://github.com/floci-io/floci/pull/821))
- **eventbridge:** `TestEventPattern` — validates a sample event against a pattern without firing any targets; returns `{"Result": <bool>}`; raises `InvalidEventPatternException` on malformed patterns ([#824](https://github.com/floci-io/floci/pull/824))
- **ses:** v2 suppression list endpoints — `PutSuppressedDestination`, `GetSuppressedDestination`, `DeleteSuppressedDestination`, `ListSuppressedDestinations` ([#813](https://github.com/floci-io/floci/pull/813))
- **s3:** virtual-hosted style addressing — `*.s3.localhost.floci.io`, `*.localhost.localstack.cloud`, and `*.s3.localhost.localstack.cloud` are now resolved as virtual-hosted buckets; `EmbeddedDnsServer` ships `localhost.localstack.cloud` and `localhost.floci.io` as built-in suffixes so spawned containers resolve correctly without extra config ([#805](https://github.com/floci-io/floci/pull/805))
- **cloudwatch-logs:** `PutSubscriptionFilter`, `DescribeSubscriptionFilters`, `DeleteSubscriptionFilter` — upsert semantics and `ByLogStream` default distribution ([#810](https://github.com/floci-io/floci/pull/810))

### Fixed

- **dynamodb:** broad conformance pass — `ValidationException` (was `InvalidParameterValue`) for table-name errors; min-length 3 enforced for all operations; `ListTables` alphabetical ordering, `Limit` validation, pagination; enum validation for `ReturnValues`/`ReturnConsumedCapacity` fires before table lookup; `ProjectionExpression` on `GetItem`, `Query`, `Scan`, `BatchGetItem`; `Select=COUNT` omits `Items`; parallel scan `Segment`/`TotalSegments` validation with hash-based sharding; reserved words rejected in `FilterExpression`/`ConditionExpression`/`UpdateExpression`; legacy `AttributesToGet`, `AttributeUpdates`, `QueryFilter`, `ScanFilter`, `KeyConditions` API; item size limit (400 KB) and number normalization on write; `TagResource`/`UntagResource`/`ListTagsOfResource` with ARN validation; `SET` into a non-existent nested map auto-creates the map ([#826](https://github.com/floci-io/floci/pull/826))
- **dynamodb:** `SET a = :v, b = a` now reads pre-update value of `a` for `b` (atomic snapshot semantics); `ClientRequestToken` idempotency with 10-minute TTL and SHA-256 body-hash conflict detection; parenthesized arithmetic `SET c = (c - :v)` now applies correctly ([#804](https://github.com/floci-io/floci/pull/804))
- **apigateway:** sibling `{proxy+}` resources now resolved by longest parent prefix, matching real AWS routing behaviour ([#811](https://github.com/floci-io/floci/pull/811))
- **cloudformation:** provision `AWS::ApiGateway::Authorizer` and wire `AuthorizerId` on methods — previously the resource was silently stubbed, leaving stacks reporting `CREATE_COMPLETE` with no authorizer registered ([#796](https://github.com/floci-io/floci/pull/796))
- **cloudformation:** preserve `Targets[].SqsParameters` (including `MessageGroupId`) when provisioning `AWS::Events::Rule` — FIFO SQS targets delivered without a `MessageGroupId` were rejected by AWS ([#793](https://github.com/floci-io/floci/pull/793))
- **sqs:** return MD5 of current request body on FIFO deduplication replay ([#786](https://github.com/floci-io/floci/pull/786))
- **sqs:** honor `MaximumMessageSize` attribute on `SendMessage` ([#782](https://github.com/floci-io/floci/pull/782))
- **sqs:** omit `Messages` field from empty `ReceiveMessage` JSON response ([#780](https://github.com/floci-io/floci/pull/780))
- **sns:** enforce message size limits on `Publish` and `PublishBatch` ([#783](https://github.com/floci-io/floci/pull/783))
- **ses:** allow `SendRawEmail` without `Source` when a `From:` header is present in the MIME payload ([#800](https://github.com/floci-io/floci/pull/800))
- **lambda:** automatically detect the Docker network of the running Floci container and use it for spawned Lambda containers — removes the need to manually set `FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK` in Docker Compose setups ([#765](https://github.com/floci-io/floci/pull/765))
- **core:** fix CRLF line-ending issues on Windows by adding `.gitattributes` normalisation rules ([#790](https://github.com/floci-io/floci/pull/790))

## [1.5.14] - 2026-05-10

### Added

- **textract:** Textract service support — `DetectDocumentText`, `AnalyzeDocument`, `StartDocumentTextDetection`, `GetDocumentTextDetection`, `StartDocumentAnalysis`, `GetDocumentAnalysis` ([#719](https://github.com/floci-io/floci/pull/719))
- **multitenancy:** per-account storage isolation across all services — if the AWS access key ID is exactly 12 digits, it is used directly as the account ID and all resources are fully isolated from other accounts ([#753](https://github.com/floci-io/floci/pull/753))
- **tls:** optional TLS/HTTPS support — set `FLOCI_TLS_ENABLED=true` to serve all endpoints over HTTPS; auto-generates and persists a self-signed certificate that includes `FLOCI_HOSTNAME`/`FLOCI_BASE_URL` in its SANs; user-provided certificates supported via `FLOCI_TLS_CERT_PATH` and `FLOCI_TLS_KEY_PATH` ([#754](https://github.com/floci-io/floci/pull/754))
- **apigatewayv2:** WebSocket data-plane support — `wss://` connections, `PostToConnection`, `GetConnection`, `DeleteConnection`, `@connect`/`@disconnect`/`$default` routes ([#712](https://github.com/floci-io/floci/pull/712))
- **cognito:** `UpdateGroup`, `ListUsersInGroup` ([#761](https://github.com/floci-io/floci/pull/761))
- **cognito:** `AdminCreateUser` with `MessageAction=RESEND` ([#755](https://github.com/floci-io/floci/pull/755))
- **ses:** `UpdateAccountSendingEnabled` (v1 Query protocol) ([#737](https://github.com/floci-io/floci/pull/737))
- **ses:** `TagResource`, `UntagResource`, `ListTagsForResource` on v2 REST JSON protocol ([#749](https://github.com/floci-io/floci/pull/749))
- **ses:** v2 tag endpoints extended to `EmailTemplate` ARNs ([#759](https://github.com/floci-io/floci/pull/759))
- **ses:** v2 tag endpoints extended to `EmailIdentity` ARNs ([#760](https://github.com/floci-io/floci/pull/760))
- **docker:** named-volume storage for child containers (RDS, ElastiCache, OpenSearch, MSK) so data survives container restarts without requiring a host bind-mount ([#743](https://github.com/floci-io/floci/pull/743))
- **docker:** ship `awslocal` CLI wrapper in the `-compat` image ([#746](https://github.com/floci-io/floci/pull/746))

### Fixed

- **cfn:** reconcile Lambda functions on stack update ([#736](https://github.com/floci-io/floci/pull/736))
- **docker:** `/etc/floci/aws/config` missing in published Docker images ([#740](https://github.com/floci-io/floci/pull/740))
- **eventbridge:** persist and forward `SqsParameters.MessageGroupId` for FIFO SQS targets ([#742](https://github.com/floci-io/floci/pull/742))
- **sqs:** tags set at `CreateQueue` not returned by `ListQueueTags` ([#747](https://github.com/floci-io/floci/pull/747))
- **apigateway:** treat `ANY` method as wildcard when matching concrete HTTP methods ([#750](https://github.com/floci-io/floci/pull/750))
- **s3:** parse `versionId` from copy-source header for `CopyObject` and multipart part-copy ([#751](https://github.com/floci-io/floci/pull/751))
- **sqs:** drop uppercase `"Tags"` key from `CreateQueue` JSON handler to match AWS wire format ([#752](https://github.com/floci-io/floci/pull/752))

### Documentation

- All service and configuration docs moved to environment-variable-first; `application.yml` reference moved to `configuration/advanced/`
- New page: [Multi-Account Isolation](docs/configuration/multi-account.md)
- New page: [TLS / HTTPS](docs/configuration/tls.md)
- Go Testcontainers page rewritten to match the published `testcontainers-floci-go` API
- LocalStack migration table completed with all mapped variables (`USE_SSL`, `CUSTOM_SSL_CERT_PATH`, `DOCKER_HOST`, `PERSIST_STATE`, `LAMBDA_REMOTE_DOCKER`)

## [1.5.13] - 2026-05-07

### Added

- **backup:** AWS Backup Phase 1 — vaults, plans, selections, on-demand jobs with simulated CREATED→RUNNING→COMPLETED lifecycle, recovery points, tagging via `SharedTagsController`, and `GetSupportedResourceTypes`
- **codedeploy:** Server platform (Phase 4) — on-premises instance management (`RegisterOnPremisesInstance`, `DeregisterOnPremisesInstance`, `GetOnPremisesInstance`, `BatchGetOnPremisesInstances`, `ListOnPremisesInstances`, `AddTagsToOnPremisesInstances`, `RemoveTagsFromOnPremisesInstances`); Server AppSpec YAML parsing; EC2 and on-premises instance resolution by tag filters; full lifecycle event execution (`ApplicationStop` → `DownloadBundle` → `BeforeInstall` → `Install` → `AfterInstall` → `ApplicationStart` → `ValidateService`); SSM Run Command integration for hook script execution with graceful degradation when SSM is unavailable; per-instance `InstanceTarget` tracking in `ListDeploymentTargets`/`BatchGetDeploymentTargets`
- **elbv2:** Lambda target type — ALB listeners forward requests to Lambda functions using the full ALB→Lambda event format (`httpMethod`, `path`, `queryStringParameters`, `multiValueQueryStringParameters`, `headers`, `multiValueHeaders`, `body`, `isBase64Encoded`); Lambda→ALB response mapping (`statusCode`, `headers`, `multiValueHeaders`, `body`, `isBase64Encoded`); Lambda target groups always report as healthy in `DescribeTargetHealth` without HTTP probing
- **eventbridge:** Archive and Replay support — `CreateArchive`, `DescribeArchive`, `UpdateArchive`, `DeleteArchive`, `ListArchives`, `StartReplay`, `DescribeReplay`, `CancelReplay`, `ListReplays`; events captured automatically to matching archives and replayed on demand ([#702](https://github.com/floci-io/floci/pull/702))
- **route53:** Route53 Phase 1 — hosted zones with auto-created SOA + NS records, `ChangeResourceRecordSets` (CREATE/UPSERT/DELETE with atomic batch validation), `ListResourceRecordSets`, change tracking (always INSYNC), health checks (create/get/list/update/delete), and per-resource tagging via `ChangeTagsForResource` / `ListTagsForResource`
- **scheduler:** `TagResource`, `UntagResource`, `ListTagsForResource` for schedule groups ([#700](https://github.com/floci-io/floci/pull/700))
- **sqs, dynamodb:** TRACE-level payload logging for request and response bodies to aid debugging ([#697](https://github.com/floci-io/floci/pull/697))
- **ssm:** Run Command — `SendCommand`, `GetCommandInvocation`, `ListCommands`, `ListCommandInvocations`, `CancelCommand`, `DescribeInstanceInformation`; `UpdateInstanceInformation` (agent registration); `ec2messages` polling protocol (`GetMessages`, `AcknowledgeMessage`, `SendReply`, `FailMessage`, `DeleteMessage`, `GetEndpoint`) so the real `amazon-ssm-agent` running inside EC2 containers can register, receive commands, and report output
- **transfer:** Transfer Family management-plane API — server lifecycle (`CreateServer`, `DescribeServer`, `DeleteServer`, `ListServers`, `StartServer`, `StopServer`, `UpdateServer`), user management (`CreateUser`, `DescribeUser`, `DeleteUser`, `ListUsers`, `UpdateUser`), SSH key management (`ImportSshPublicKey`, `DeleteSshPublicKey`), and tagging (`TagResource`, `UntagResource`, `ListTagsForResource`)

### Fixed

- **appconfig:** use capital `"Tags"` body key in `TagResource` / `ListTagsForResource` to match AWS SDK wire format ([#704](https://github.com/floci-io/floci/pull/704))
- **docker:** respect `DOCKER_HOST` env var and handle bare `host:port` values without a URI scheme ([#705](https://github.com/floci-io/floci/pull/705))
- **ec2:** `AssociateRouteTable` now returns `<associationState>` in the response; `DescribeRouteTables` supports the `association.route-table-association-id` filter; `DescribeTags` correctly applies `resource-id`, `resource-type`, `key`, and `value` filters ([#706](https://github.com/floci-io/floci/pull/706))

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

[Unreleased]: https://github.com/floci-io/floci/compare/1.5.26...HEAD
[1.5.26]: https://github.com/floci-io/floci/compare/1.5.25...1.5.26
[1.5.25]: https://github.com/floci-io/floci/compare/1.5.24...1.5.25
[1.5.24]: https://github.com/floci-io/floci/compare/1.5.23...1.5.24
[1.5.23]: https://github.com/floci-io/floci/compare/1.5.22...1.5.23
[1.5.22]: https://github.com/floci-io/floci/compare/1.5.21...1.5.22
[1.5.21]: https://github.com/floci-io/floci/compare/1.5.20...1.5.21
[1.5.20]: https://github.com/floci-io/floci/compare/1.5.19...1.5.20
[1.5.19]: https://github.com/floci-io/floci/compare/1.5.18...1.5.19
[1.5.18]: https://github.com/floci-io/floci/compare/1.5.17...1.5.18
[1.5.17]: https://github.com/floci-io/floci/compare/1.5.16...1.5.17
[1.5.16]: https://github.com/floci-io/floci/compare/1.5.15...1.5.16
[1.5.15]: https://github.com/floci-io/floci/compare/1.5.14...1.5.15
[1.5.14]: https://github.com/floci-io/floci/compare/1.5.13...1.5.14
[1.5.13]: https://github.com/floci-io/floci/compare/1.5.12...1.5.13
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
