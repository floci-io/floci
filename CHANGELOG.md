## [Unreleased]


### Bug Fixes

* unify service metadata behind a descriptor-backed catalog for enablement, routing, and storage lookups
* include `ec2` and `ecs` in enabled-service reporting and enforce disabled gating for ACM and ECS targeted requests
* return protocol-correct JSON disabled responses for auth-only REST GETs instead of falling back to XML
* honor `floci.storage.services.acm.*` overrides in `StorageFactory`

## [1.5.2](https://github.com/floci-io/floci/compare/1.5.1...1.5.2) (2026-04-10)


### Bug Fixes

* **apigateway:** implement v2 management api and cfn provisioning ([#323](https://github.com/floci-io/floci/issues/323)) ([30f9cd3](https://github.com/floci-io/floci/commit/30f9cd3d04b24f2e83597e5edaef9b223f115b10))
* **cloudwatch:** implement tagging support for metrics and alarms ([#320](https://github.com/floci-io/floci/issues/320)) ([c426e80](https://github.com/floci-io/floci/commit/c426e805c2f1ed9f96eeb44dc9e70cde0e70a88d))
* **dynamodb:** handle null for Java AWS SDK v2 DynamoDB EnhancedClient correctly ([#309](https://github.com/floci-io/floci/issues/309)) ([8bac818](https://github.com/floci-io/floci/commit/8bac818))
* **dynamodb:** implement list_append with if_not_exists support ([#317](https://github.com/floci-io/floci/issues/317)) ([0eb908a](https://github.com/floci-io/floci/commit/0eb908a))
* **dynamodb:** remove duplicate list_append handler that breaks nested expressions ([#321](https://github.com/floci-io/floci/issues/321)) ([92ba56b](https://github.com/floci-io/floci/commit/92ba56b))
* **rds:** DescribeDBInstances returns empty results due to wrong XML element names and missing Filters support ([#319](https://github.com/floci-io/floci/issues/319)) ([473fece](https://github.com/floci-io/floci/commit/473fece))
* **s3:** preserve leading slashes in object keys to prevent key collisions ([#286](https://github.com/floci-io/floci/issues/286)) ([c7d5c55](https://github.com/floci-io/floci/commit/c7d5c55))
* support LocalStack-compatible `_user_request_` URL for API Gateway execution ([#314](https://github.com/floci-io/floci/issues/314)) ([55325ec](https://github.com/floci-io/floci/commit/55325ec))


### Features

* **kinesis:** add IncreaseStreamRetentionPeriod and DecreaseStreamRetentionPeriod ([#305](https://github.com/floci-io/floci/issues/305)) ([a770b61](https://github.com/floci-io/floci/commit/a770b61))
* **kinesis:** resolve stream name from StreamARN parameter ([#304](https://github.com/floci-io/floci/issues/304)) ([202d6eb](https://github.com/floci-io/floci/commit/202d6eb))
* **kms:** add GetKeyRotationStatus, EnableKeyRotation, and DisableKeyRotation ([#290](https://github.com/floci-io/floci/issues/290)) ([1fa7ae7](https://github.com/floci-io/floci/commit/1fa7ae7))
* **s3:** preserve Cache-Control header on PutObject, GetObject, HeadObject, and CopyObject ([#313](https://github.com/floci-io/floci/issues/313)) ([89a0744](https://github.com/floci-io/floci/commit/89a0744))

## [1.5.1](https://github.com/floci-io/floci/compare/1.5.0...1.5.1) (2026-04-09)


### Bug Fixes

* merge branch 'main' into release/1.x ([b2075be](https://github.com/floci-io/floci/commit/b2075be55cc3955db03f6b6df5ee7f7f633eb5ef))
* native image build failure due to SecureRandom in CognitoSrpHelper ([e83f6d6](https://github.com/floci-io/floci/commit/e83f6d6cbd0fe8e348e228b18ef2163ab8a80271))

# [1.5.0](https://github.com/floci-io/floci/compare/1.4.0...1.5.0) (2026-04-09)


### Bug Fixes

* append march x86-64-v2 ([#303](https://github.com/floci-io/floci/issues/303)) ([ddccbe0](https://github.com/floci-io/floci/commit/ddccbe067a22d415b0b52d65bee5d1293087d4e6))
* DynamoDB DescribeTable returns Projection.NonKeyAttributes ([#300](https://github.com/floci-io/floci/issues/300)) ([552e71e](https://github.com/floci-io/floci/commit/552e71e76f19bc31b7ee40fd084441a10d6294ca))
* implement missing RDS resource identifiers and fix filtering ([#231](https://github.com/floci-io/floci/issues/231)) ([#302](https://github.com/floci-io/floci/issues/302)) ([7af3600](https://github.com/floci-io/floci/commit/7af3600bcaa4652ad57ceebe211ad3ba0e336adb))
* Implement S3 Lambda notifications ([#278](https://github.com/floci-io/floci/issues/278)) ([04cbeb5](https://github.com/floci-io/floci/commit/04cbeb542f377b46cd3b84806628c14ab68cada2))
* implement SRP-6a authentication for Cognito ([#284](https://github.com/floci-io/floci/issues/284)) ([#298](https://github.com/floci-io/floci/issues/298)) ([7c6e6a6](https://github.com/floci-io/floci/commit/7c6e6a6dfaa5fc73ebfa41a17ab40f506baba3c6))
* merge branch 'main' into release/1.x ([f32f4a9](https://github.com/floci-io/floci/commit/f32f4a97a7e2cdcf5186b8e525efbd7a2df9c3e7))
* register Xerces XML resource bundles for native image ([#293](https://github.com/floci-io/floci/issues/293)) ([#296](https://github.com/floci-io/floci/issues/296)) ([0bad933](https://github.com/floci-io/floci/commit/0bad9338f9269fc9ddf4e2f01bc71dda6ca418b9))
* **s3:** use case-insensitive field lookup for presigned POST policy validation ([#289](https://github.com/floci-io/floci/issues/289)) ([3d2cc1c](https://github.com/floci-io/floci/commit/3d2cc1c95e5a39b8006a5a92366bc5dc22842bb0))
* **s3:** use ConfigProvider for runtime config lookup in S3VirtualHostFilter ([#288](https://github.com/floci-io/floci/issues/288)) ([944fddf](https://github.com/floci-io/floci/commit/944fddf5acb8f9fa394550f5a4f088f0ef6ad3ee))


### Features

* **cloudformation:** add AWS::Events::Rule provisioning support ([#261](https://github.com/floci-io/floci/issues/261)) ([c475e52](https://github.com/floci-io/floci/commit/c475e529845d413c23ec70165789864d013463e7))
* **eventbridge:** add InputTransformer support and S3 event notifications ([#294](https://github.com/floci-io/floci/issues/294)) ([9ca82e5](https://github.com/floci-io/floci/commit/9ca82e5748cb700760aca3efd3c34a06a5766ec2)), closes [#140](https://github.com/floci-io/floci/issues/140)
* load persisted dynamodb streams on start up ([#299](https://github.com/floci-io/floci/issues/299)) ([ae49bfb](https://github.com/floci-io/floci/commit/ae49bfb378b00c251b5d952ba16d81e892f364bd))

# [1.4.0](https://github.com/floci-io/floci/compare/1.3.0...1.4.0) (2026-04-08)


### Bug Fixes

* add list_append support to DynamoDB update expressions ([#277](https://github.com/floci-io/floci/issues/277)) ([b723a9c](https://github.com/floci-io/floci/commit/b723a9c519b15372a4dc8f01a434b43e504bbc8d))
* default shell executable to /bin/sh for Alpine compatibility ([#241](https://github.com/floci-io/floci/issues/241)) ([d02a1d9](https://github.com/floci-io/floci/commit/d02a1d9a13bddd46db5fbe0522a0d96fca0facda))
* drain warm pool containers on server shutdown ([#274](https://github.com/floci-io/floci/issues/274)) ([caabf46](https://github.com/floci-io/floci/commit/caabf46b1c61f5ce49b109dbbb2b9d8b20d744d6))
* dynamodb support add function multiple values ([#263](https://github.com/floci-io/floci/issues/263)) ([1ddc8a3](https://github.com/floci-io/floci/commit/1ddc8a32f7523d2c35b861e1987e39b2ff264e26))
* handle base64-encoded ACM cert imports ([#248](https://github.com/floci-io/floci/issues/248)) ([1391691](https://github.com/floci-io/floci/commit/13916913e8f18f228217620f14609dca3798b3cb))
* include ProvisionedThroughput in DynamoDB GSI responses ([#273](https://github.com/floci-io/floci/issues/273)) ([399a96d](https://github.com/floci-io/floci/commit/399a96d70c0510a62a69bcfd42cf5566f8718e60))
* issues 226 227 ([#257](https://github.com/floci-io/floci/issues/257)) ([81f1a01](https://github.com/floci-io/floci/commit/81f1a01c39c090c1a82b71c0c79b0fe50b3831b7))
* make EmulatorLifecycle use more idiomatic Quarkus code ([#190](https://github.com/floci-io/floci/issues/190)) ([7ea586e](https://github.com/floci-io/floci/commit/7ea586e446ccc89bd38f8ee6d11ba83c9e6f84a3))
* merge branch 'main' into release/1.x ([1feec3a](https://github.com/floci-io/floci/commit/1feec3a29222adce872ba2926e9935bd7d0f3cdb))
* removing log file ([17841d2](https://github.com/floci-io/floci/commit/17841d2d5afd7ebea7149f0745a55b3774d86ef0))
* resolve Cognito auth, token, and user lookup issues ([#218](https://github.com/floci-io/floci/issues/218) [#220](https://github.com/floci-io/floci/issues/220) [#228](https://github.com/floci-io/floci/issues/228) [#229](https://github.com/floci-io/floci/issues/229) [#233](https://github.com/floci-io/floci/issues/233) [#234](https://github.com/floci-io/floci/issues/234) [#235](https://github.com/floci-io/floci/issues/235)) ([#279](https://github.com/floci-io/floci/issues/279)) ([5e8b39c](https://github.com/floci-io/floci/commit/5e8b39c1aaecd738bb9e7ba36580d5c3da89f276))
* return 400 when encoded s3 copy source is malformed ([#244](https://github.com/floci-io/floci/issues/244)) ([f4f1752](https://github.com/floci-io/floci/commit/f4f1752b0daa61e16cd9aded6443ec355a314b0e))
* **s3:** enforce presigned POST policy conditions (eq, starts-with, content-type) ([#203](https://github.com/floci-io/floci/issues/203)) ([cd1759a](https://github.com/floci-io/floci/commit/cd1759aebed320939c7b13fce4a98909ed6d2235))
* **s3:** versioning IsTruncated, PublicAccessBlock, ListObjectsV2 pagination, K8s virtual host routing ([#276](https://github.com/floci-io/floci/issues/276)) ([6d5839b](https://github.com/floci-io/floci/commit/6d5839bec88a38a9690f5111fc8eda1c2def254c))


### Features

* add KMS GetKeyPolicy, PutKeyPolicy and fix CreateKey Tags ([#258](https://github.com/floci-io/floci/issues/258) [#259](https://github.com/floci-io/floci/issues/259) [#269](https://github.com/floci-io/floci/issues/269)) ([#280](https://github.com/floci-io/floci/issues/280)) ([4724db9](https://github.com/floci-io/floci/commit/4724db97be2bc719f4d76e0edfc12202cd1d6e21))
* add SES V2 REST JSON protocol support ([#265](https://github.com/floci-io/floci/issues/265)) ([e7ab687](https://github.com/floci-io/floci/commit/e7ab687a6a066bd38548d3c425243ad5e024840a))
* **lambda:** add missing runtimes, fix handler validation, long path… ([#256](https://github.com/floci-io/floci/issues/256)) ([0ef6f87](https://github.com/floci-io/floci/commit/0ef6f87cba2880c321c91c3d90d769603511aa56))
* **scheduler:** add EventBridge Scheduler service ([#260](https://github.com/floci-io/floci/issues/260)) ([48b6ca3](https://github.com/floci-io/floci/commit/48b6ca347583eb6ace95a69797a2688a3d3e953d))
* **secretsmanager:** add support for BatchGetSecretValue ([#115](https://github.com/floci-io/floci/issues/115)) ([#264](https://github.com/floci-io/floci/issues/264)) ([37026b7](https://github.com/floci-io/floci/commit/37026b75f1bad702aa649e61e188a25cc2fa8924))
* **sfn:** nested state machine execution and activity support ([#254](https://github.com/floci-io/floci/issues/254), [#91](https://github.com/floci-io/floci/issues/91)) ([#266](https://github.com/floci-io/floci/issues/266)) ([18bee5b](https://github.com/floci-io/floci/commit/18bee5b278aaaed7e018111c6c146051d862511a))
* use AWS-specific content type within the response of all JSON-based controllers ([#240](https://github.com/floci-io/floci/issues/240)) ([b4afdbb](https://github.com/floci-io/floci/commit/b4afdbb8421f9101eb3c24159443647160b331d5))

# [1.3.0](https://github.com/floci-io/floci/compare/1.2.0...1.3.0) (2026-04-06)


### Bug Fixes

* compilation issue ([10edd2b](https://github.com/floci-io/floci/commit/10edd2bc38523d4f55226b653696f843129a38c7))
* fall back to Docker bridge IP when host.docker.internal is unresolvable ([#216](https://github.com/floci-io/floci/issues/216)) ([b973b70](https://github.com/floci-io/floci/commit/b973b7060d4cadbf54b6c4bac4f28c69bbf5cdab))
* **lambda:** copy code to TASK_DIR for provided runtimes ([#206](https://github.com/floci-io/floci/issues/206)) ([0de7931](https://github.com/floci-io/floci/commit/0de793148063f37973f876bbcbe9f76a1fcdead0))
* **lambda:** honour ReportBatchItemFailures in SQS ESM ([#208](https://github.com/floci-io/floci/issues/208)) ([55b2f29](https://github.com/floci-io/floci/commit/55b2f292cc879be814a2e3e3675255ecf382f618))
* **lambda:** support Code.S3Bucket + Code.S3Key in CreateFunction and UpdateFunctionCode ([#219](https://github.com/floci-io/floci/issues/219)) ([d4ebc8e](https://github.com/floci-io/floci/commit/d4ebc8e82087a57dfd86a7bcff27ef8739f12b70))
* merge branch 'main' into release/1.x ([82d3184](https://github.com/floci-io/floci/commit/82d3184498796a4f177a6af0bf0fffb066f245ab))
* **ses:** add missing Result element to query protocol responses ([#207](https://github.com/floci-io/floci/issues/207)) ([80e2054](https://github.com/floci-io/floci/commit/80e205452b7966c5bb5dcda66d1a5f4159f5161a))
* **sns:** make Subscribe idempotent for same topic+protocol+endpoint ([#185](https://github.com/floci-io/floci/issues/185)) ([858775c](https://github.com/floci-io/floci/commit/858775c38edb23f8e79b1b121640549a10c16ebb))


### Features

* add GlobalSecondaryIndexUpdates support in DynamoDB UpdateTable ([#222](https://github.com/floci-io/floci/issues/222)) ([4e6e953](https://github.com/floci-io/floci/commit/4e6e9533b5983530a9b9bc000abf9349e620dcb6)), closes [#221](https://github.com/floci-io/floci/issues/221)
* add scheduled rules support for EventBridge Rules ([#217](https://github.com/floci-io/floci/issues/217)) ([e8c6440](https://github.com/floci-io/floci/commit/e8c64404899f011493d9ce477ce5db306d76b76f))
* **dynamodb:** add ScanFilter support for Scan operation ([#175](https://github.com/floci-io/floci/issues/175)) ([fe4ffd1](https://github.com/floci-io/floci/commit/fe4ffd1ee4f76bff685fd16162327b69f11da3d7))
* **ec2:** add EC2 service with 61 operations, integration tests, and docs ([#213](https://github.com/floci-io/floci/issues/213)) ([2859d25](https://github.com/floci-io/floci/commit/2859d257b50824548292467903c9ab3210325974))
* **ecs:** adding ecs service ([#209](https://github.com/floci-io/floci/issues/209)) ([07a7a97](https://github.com/floci-io/floci/commit/07a7a97b4747977641ec16a1e1f06e7124016683))
* **eventbridge:** forward resources array and support resources pattern matching ([#210](https://github.com/floci-io/floci/issues/210)) ([6d49f09](https://github.com/floci-io/floci/commit/6d49f0983f0d89c896115fdcc98095b9f50ed76a))
* **lambda:** add AddPermission, GetPolicy, ListTags, ListLayerVersions, etc endpoints ([#223](https://github.com/floci-io/floci/issues/223)) ([c79f02f](https://github.com/floci-io/floci/commit/c79f02f26f6610e67be5b7ac53b8dc26af4fb747))
* **sfn:** JSONata improvements, States.* intrinsics, DynamoDB ConditionExpression, StartSyncExecution ([#205](https://github.com/floci-io/floci/issues/205)) ([53dd7a6](https://github.com/floci-io/floci/commit/53dd7a68dd79f148265d24eea016d57f043cda08))

# [1.2.0](https://github.com/hectorvent/floci/compare/1.1.0...1.2.0) (2026-04-04)


### Bug Fixes

* adding aws-cli in its own floci image hectorvent/floci:x.y.z-aws ([#151](https://github.com/hectorvent/floci/issues/151)) ([aba9593](https://github.com/hectorvent/floci/commit/aba95933cfa774d6397dca9e47f5d6411249c393))
* **cognito:** auto-generate sub, fix JWT sub claim, add AdminUserGlobalSignOut ([#68](https://github.com/hectorvent/floci/issues/68)) ([#183](https://github.com/hectorvent/floci/issues/183)) ([9d6181c](https://github.com/hectorvent/floci/commit/9d6181c5479a856064892da3ce39339a6bd8f4ca))
* **cognito:** enrich User Pool responses and implement MfaConfig stub ([#198](https://github.com/hectorvent/floci/issues/198)) ([441d9f1](https://github.com/hectorvent/floci/commit/441d9f179ec1430d7b7d187ac66b7cdc8741c37e))
* **Cognito:** OAuth/OIDC parity for RS256/JWKS, /oauth2/token, and OAuth app-client settings ([#97](https://github.com/hectorvent/floci/issues/97)) ([a4af506](https://github.com/hectorvent/floci/commit/a4af506c1509d8673dc2b2d9bfd9b8d5e1784aa7))
* **core:** globally inject aws request-id headers for sdk compatibility ([#146](https://github.com/hectorvent/floci/issues/146)) ([35e129d](https://github.com/hectorvent/floci/commit/35e129d02482bfd2a7b18ca30e8939b16c2a10cc)), closes [#145](https://github.com/hectorvent/floci/issues/145)
* defer startup hooks until HTTP server is ready ([#157](https://github.com/hectorvent/floci/issues/157)) ([#159](https://github.com/hectorvent/floci/issues/159)) ([59c24c5](https://github.com/hectorvent/floci/commit/59c24c5e4e44f48b225c1d2fc99aaa0fe3f45957))
* **dynamodb:** fix FilterExpression for BOOL types, List/Set contains, and nested attribute paths ([#137](https://github.com/hectorvent/floci/issues/137)) ([453555a](https://github.com/hectorvent/floci/commit/453555a12474a9429dbaaf7b91026570c47c4fac)), closes [#126](https://github.com/hectorvent/floci/issues/126)
* **lambda:** copy function code to /var/runtime for provided runtimes ([#114](https://github.com/hectorvent/floci/issues/114)) ([a5ad6cf](https://github.com/hectorvent/floci/commit/a5ad6cf7bc90339ba384d791bfd1ac22c7b1e6b3))
* merge branch 'main' into release/1.x ([0105e36](https://github.com/hectorvent/floci/commit/0105e36d873cc751f064bf08ca34444f4d19095e))
* polish HealthController ([#188](https://github.com/hectorvent/floci/issues/188)) ([084237d](https://github.com/hectorvent/floci/commit/084237ddf4ba2ea6b0f9de13241a85055c5fb007))
* remove private modifier on injected field ([#186](https://github.com/hectorvent/floci/issues/186)) ([ebc0661](https://github.com/hectorvent/floci/commit/ebc06616e717f095007b0888cee7357151343e0b))
* resolve CloudFormation Lambda Code.S3Key base64 decode error ([#62](https://github.com/hectorvent/floci/issues/62)) ([78be523](https://github.com/hectorvent/floci/commit/78be5232e212ffb05bba651c2c39573c762586b7))
* resolve numeric ExpressionAttributeNames in DynamoDB expressions ([#192](https://github.com/hectorvent/floci/issues/192)) ([d93296a](https://github.com/hectorvent/floci/commit/d93296a7663414cdcb4cf20d0018e7ecabc987bf))
* return stable cursor tokens in GetLogEvents to fix SDK pagination loop ([#90](https://github.com/hectorvent/floci/issues/90)) ([#184](https://github.com/hectorvent/floci/issues/184)) ([7354663](https://github.com/hectorvent/floci/commit/73546637ec0e7385ff19d28992beb47025e5db42))
* **s3:** Evaluate S3 CORS against incoming HTTP Requests ([#131](https://github.com/hectorvent/floci/issues/131)) ([e78c833](https://github.com/hectorvent/floci/commit/e78c8337948c1c680a9596ab4b55d63d84b4c5c8))
* **s3:** list part for multipart upload ([#164](https://github.com/hectorvent/floci/issues/164)) ([7253559](https://github.com/hectorvent/floci/commit/7253559207ed0d86f5a7b293e42fff7be5d080a2))
* **s3:** persist Content-Encoding header on S3 objects ([#57](https://github.com/hectorvent/floci/issues/57)) ([ff2f68d](https://github.com/hectorvent/floci/commit/ff2f68d8ab20559494661fb14981c68812edbcd9))
* **s3:** prevent S3VirtualHostFilter from hijacking non-S3 requests ([#199](https://github.com/hectorvent/floci/issues/199)) ([59cdc3f](https://github.com/hectorvent/floci/commit/59cdc3fea69bb42a44f53c094582961ca44f2e8c))
* **s3:** resolve file/folder name collision on persistent filesystem ([#134](https://github.com/hectorvent/floci/issues/134)) ([020a546](https://github.com/hectorvent/floci/commit/020a54642d8863c3c94c7385d94aaaa37aa05b7a))
* **s3:** return CommonPrefixes in ListObjects when delimiter is specified ([#133](https://github.com/hectorvent/floci/issues/133)) ([845ac85](https://github.com/hectorvent/floci/commit/845ac853632130b1835f6c35edfc0efc39cf32a1))
* **secretsmanager:** return KmsKeyId in DescribeSecret and improve ListSecrets ([#195](https://github.com/hectorvent/floci/issues/195)) ([1e44f39](https://github.com/hectorvent/floci/commit/1e44f39c7c3f0d0ea89c1124dae318b8cc46d363))
* **sns:** enforce FilterPolicy on message delivery ([#53](https://github.com/hectorvent/floci/issues/53)) ([2f875d4](https://github.com/hectorvent/floci/commit/2f875d41694ace585f00865e3b82987b40ae92a8)), closes [#49](https://github.com/hectorvent/floci/issues/49)
* **sns:** honor RawMessageDelivery attribute for SQS subscriptions ([#54](https://github.com/hectorvent/floci/issues/54)) ([b762bec](https://github.com/hectorvent/floci/commit/b762becaea3795d2a6320e10dd290a84088e9b2b))
* **sns:** pass messageDeduplicationId from FIFO topics to SQS FIFO queues ([#171](https://github.com/hectorvent/floci/issues/171)) ([4529823](https://github.com/hectorvent/floci/commit/452982355716f5ada53c1f8bdc67293bc2282963))
* **sqs:** route queue URL path requests to SQS handler ([#153](https://github.com/hectorvent/floci/issues/153)) ([6bbc9d9](https://github.com/hectorvent/floci/commit/6bbc9d93755e5bbeb17ec6ee39319711f74f3ebb)), closes [#99](https://github.com/hectorvent/floci/issues/99) [#17](https://github.com/hectorvent/floci/issues/17)
* **sqs:** support binary message attributes and fix MD5OfMessageAttributes ([#168](https://github.com/hectorvent/floci/issues/168)) ([5440ae8](https://github.com/hectorvent/floci/commit/5440ae8a558c18157bcf0699caf3855c36494912))
* **sqs:** translate Query-protocol error codes to JSON __type equivalents ([#59](https://github.com/hectorvent/floci/issues/59)) ([7d6cf61](https://github.com/hectorvent/floci/commit/7d6cf6179deb642b1d3add2b806eeb5814e18280))
* support DynamoDB Query BETWEEN and ScanIndexForward=false ([#160](https://github.com/hectorvent/floci/issues/160)) ([cf2c705](https://github.com/hectorvent/floci/commit/cf2c705b7d2e2f92956c8d7e5ffa96fbeb0dc302))
* wrong method call in test ([665af53](https://github.com/hectorvent/floci/commit/665af531498c65bef5d5117ccc3ba57e92c5af3d))


### Features

* add support of Cloudformation mapping and Fn::FindInMap function ([#101](https://github.com/hectorvent/floci/issues/101)) ([eef6698](https://github.com/hectorvent/floci/commit/eef66983d90e1f3cee6aabdb3bc4e1205b68f83e))
* **cloudwatch-logs:** add ListTagsForResource, TagResource, and UntagResource support ([#172](https://github.com/hectorvent/floci/issues/172)) ([835f8c6](https://github.com/hectorvent/floci/commit/835f8c6ff420691e3efe703d3c24380cbb245e37)), closes [#77](https://github.com/hectorvent/floci/issues/77)
* **cognito:** add group management support ([#149](https://github.com/hectorvent/floci/issues/149)) ([75bf3c3](https://github.com/hectorvent/floci/commit/75bf3c3bdbe24a46d4a31c8b2fef687e80d64df8))
* health endpoint ([#139](https://github.com/hectorvent/floci/issues/139)) ([fb42087](https://github.com/hectorvent/floci/commit/fb42087631dcd7980b6fa3706671f8044cb32c84))
* implement UploadPartCopy for S3 multipart uploads ([#98](https://github.com/hectorvent/floci/issues/98)) ([d1b9a9c](https://github.com/hectorvent/floci/commit/d1b9a9ca6d8dd8df7235481ce00720ea0277ea59))
* **lambda:** implement ListVersionsByFunction API ([#182](https://github.com/hectorvent/floci/issues/182)) ([#193](https://github.com/hectorvent/floci/issues/193)) ([ecf25d4](https://github.com/hectorvent/floci/commit/ecf25d47367524c7ef26d4b7691b472b10e9d345))
* officially support Docker named volumes for Native images ([#155](https://github.com/hectorvent/floci/issues/155)) ([4fc9398](https://github.com/hectorvent/floci/commit/4fc9398df4a68b58f218102e0e032e4d9e5d48b1))
* **s3:** support Filter rules in PutBucketNotificationConfiguration ([#178](https://github.com/hectorvent/floci/issues/178)) ([ef06fc3](https://github.com/hectorvent/floci/commit/ef06fc34933b6b043501ae706e842f127c19543b))
* support GenerateSecretString and Description for AWS::SecretsManager::Secret in CloudFormation ([#176](https://github.com/hectorvent/floci/issues/176)) ([f994b95](https://github.com/hectorvent/floci/commit/f994b9545b02f6df7289d0f9e9da5dc2dfe23dc8))
* support GSI and LSI in CloudFormation DynamoDB table provisioning ([#125](https://github.com/hectorvent/floci/issues/125)) ([48bee44](https://github.com/hectorvent/floci/commit/48bee44634dc9c665c15d8aaefac7378dd6c4970))

# [1.1.0](https://github.com/hectorvent/floci/compare/1.0.11...1.1.0) (2026-03-31)


### Bug Fixes

* added versionId to S3 notifications for versioning enabled buckets. ([#135](https://github.com/hectorvent/floci/issues/135)) ([3d67bc4](https://github.com/hectorvent/floci/commit/3d67bc4ba38da69fe116a865e442cfc30a33c1b3))
* align S3 CreateBucket and HeadBucket region behavior with AWS ([#75](https://github.com/hectorvent/floci/issues/75)) ([8380166](https://github.com/hectorvent/floci/commit/838016660cb58daa0e06892c3d7aa554eb191f62))
* DynamoDB table creation compatibility with Terraform AWS provider v6 ([#89](https://github.com/hectorvent/floci/issues/89)) ([7b87bf2](https://github.com/hectorvent/floci/commit/7b87bf2c1fa8f9cff7aef4be488d7b2cbf3fe26d))
* **dynamodb:** apply filter expressions in Query ([#123](https://github.com/hectorvent/floci/issues/123)) ([8b6f4fa](https://github.com/hectorvent/floci/commit/8b6f4fa4f51b73240f5b685bd835172fb996d780))
* **dynamodb:** respect `if_not_exists` for `update_item` ([#102](https://github.com/hectorvent/floci/issues/102)) ([8882a8e](https://github.com/hectorvent/floci/commit/8882a8ebe2213e383ff719793c137b50a937c6c0))
* for no-such-key with non-ascii key ([#112](https://github.com/hectorvent/floci/issues/112)) ([ab072cf](https://github.com/hectorvent/floci/commit/ab072cf660f784ab5a65077573e3adf36990a2ae))
* **KMS:** Allow arn and alias to encrypt ([#69](https://github.com/hectorvent/floci/issues/69)) ([fa4e107](https://github.com/hectorvent/floci/commit/fa4e107572792b5cc4dc6e3f4b323695a4a9add7))
* resolve compatibility test failures across multiple services ([#109](https://github.com/hectorvent/floci/issues/109)) ([1377868](https://github.com/hectorvent/floci/commit/1377868094389616308e3d379c9979a883051f9a))
* **s3:** allow upload up to 512MB by default. close [#19](https://github.com/hectorvent/floci/issues/19) ([#110](https://github.com/hectorvent/floci/issues/110)) ([3891232](https://github.com/hectorvent/floci/commit/38912326c96741022fc05cc3c0ddc8c1612b906a))
* **s3:** expose inMemory flag in test constructor to fix S3 disk-persistence tests ([#136](https://github.com/hectorvent/floci/issues/136)) ([522b369](https://github.com/hectorvent/floci/commit/522b3696a6ae3aa8bfb3b02f4284a507c91ffa94))
* **sns:** add PublishBatch support to JSON protocol handler ([543df05](https://github.com/hectorvent/floci/commit/543df0539b2e68ad2795ce9deb0557624aeea70a))
* Storage load after backend is created ([#71](https://github.com/hectorvent/floci/issues/71)) ([c95dd10](https://github.com/hectorvent/floci/commit/c95dd1068e7910e3c19bd888be421469b64a1ad9))
* **storage:** fix storage global config issue and memory s3 directory creation ([b84a128](https://github.com/hectorvent/floci/commit/b84a1281f86f01a3de656748f8d6b90dd20e798f))


### Features

* add ACM support ([#21](https://github.com/hectorvent/floci/issues/21)) ([8a8d55d](https://github.com/hectorvent/floci/commit/8a8d55d9727c41eb0f5aa8a434ce792e64cfeed2))
* add HOSTNAME_EXTERNAL support for multi-container Docker setups ([#82](https://github.com/hectorvent/floci/issues/82)) ([20b40c1](https://github.com/hectorvent/floci/commit/20b40c1565b87e203dd6ce3d453e019ab0557e80)), closes [#81](https://github.com/hectorvent/floci/issues/81)
* add JSONata query language support for Step Functions ([#84](https://github.com/hectorvent/floci/issues/84)) ([f82b370](https://github.com/hectorvent/floci/commit/f82b370ab2e38f40306c7e330d97da4f720fe828))
* add Kinesis ListShards operation ([#61](https://github.com/hectorvent/floci/issues/61)) ([6ff8190](https://github.com/hectorvent/floci/commit/6ff819083d48de01317c1de7f12eaa7f23a638a4))
* add opensearch service emulation ([#85](https://github.com/hectorvent/floci/issues/85)) ([#132](https://github.com/hectorvent/floci/issues/132)) ([68b8ed8](https://github.com/hectorvent/floci/commit/68b8ed883a45ac35690c474a7d82179db642b145))
* add SES (Simple Email Service) emulation ([#14](https://github.com/hectorvent/floci/issues/14)) ([9bf23d5](https://github.com/hectorvent/floci/commit/9bf23d5513ddeeca83b9185baea34b5fb2dbeaa9))
* Adding/Fixing support for virtual hosts ([#88](https://github.com/hectorvent/floci/issues/88)) ([26facf2](https://github.com/hectorvent/floci/commit/26facf26e5d6b1cfd6dda0825e43d02645cdb0fa))
* **APIGW:** add AWS integration type for API Gateway REST v1 ([#108](https://github.com/hectorvent/floci/issues/108)) ([bb4f000](https://github.com/hectorvent/floci/commit/bb4f000914caea64f27c78ce8abab85c1ffac344))
* **APIGW:** OpenAPI/Swagger import, models, and request validation ([#113](https://github.com/hectorvent/floci/issues/113)) ([d1d7ec3](https://github.com/hectorvent/floci/commit/d1d7ec3bd31281a95626042ad71c4d50df0610ab))
* docker image with awscli Closes: [#66](https://github.com/hectorvent/floci/issues/66)) ([#95](https://github.com/hectorvent/floci/issues/95)) ([823770e](https://github.com/hectorvent/floci/commit/823770e46325f47252ba3f3054f34710e51f597d))
* implement GetRandomPassword for Secrets Manager ([#76](https://github.com/hectorvent/floci/issues/76)) ([#80](https://github.com/hectorvent/floci/issues/80)) ([c57d9eb](https://github.com/hectorvent/floci/commit/c57d9ebcf88f1e9ed31567f9b5989a17588ebf98))
* **lifecycle:** add support for startup and shutdown initialization hooks ([#128](https://github.com/hectorvent/floci/issues/128)) ([7b2576f](https://github.com/hectorvent/floci/commit/7b2576fb42e52e49bd897490b0ace29d113b786d))
* **s3:** add conditional request headers (If-Match, If-None-Match, If-Modified-Since, If-Unmodified-Since) ([#48](https://github.com/hectorvent/floci/issues/48)) ([66af545](https://github.com/hectorvent/floci/commit/66af545053595db74a16afc701b849bf078cbb23)), closes [#46](https://github.com/hectorvent/floci/issues/46)
* **s3:** add presigned POST upload support ([#120](https://github.com/hectorvent/floci/issues/120)) ([1e59f8d](https://github.com/hectorvent/floci/commit/1e59f8dc59161b830887a31b3b3441cad34c781b))
* **s3:** add Range header support for GetObject ([#44](https://github.com/hectorvent/floci/issues/44)) ([b0f5ae2](https://github.com/hectorvent/floci/commit/b0f5ae22cd9bbf9999eef49abd39402781d8f5fc)), closes [#40](https://github.com/hectorvent/floci/issues/40)
* **SFN:** add DynamoDB AWS SDK integration and complete optimized updateItem ([#103](https://github.com/hectorvent/floci/issues/103)) ([4766a7e](https://github.com/hectorvent/floci/commit/4766a7e6f5ace562f9c620b4aa18f1de71a701c5))

## [1.0.11](https://github.com/hectorvent/floci/compare/1.0.10...1.0.11) (2026-03-24)


### Bug Fixes

* add S3 GetObjectAttributes and metadata parity ([#29](https://github.com/hectorvent/floci/issues/29)) ([7d5890a](https://github.com/hectorvent/floci/commit/7d5890a6440ca72d565f3d987afa380825ba5861))

## [1.0.10](https://github.com/hectorvent/floci/compare/1.0.9...1.0.10) (2026-03-24)


### Bug Fixes

* return versionId in CompleteMultipartUpload response ([#35](https://github.com/hectorvent/floci/issues/35)) ([6e8713d](https://github.com/hectorvent/floci/commit/6e8713d9fe4e1b3f6536f979899209daa00b0a04)), closes [hectorvent/floci#32](https://github.com/hectorvent/floci/issues/32)

## [1.0.9](https://github.com/hectorvent/floci/compare/1.0.8...1.0.9) (2026-03-24)


### Bug Fixes

* add ruby lambda runtime support ([#18](https://github.com/hectorvent/floci/issues/18)) ([38bdaf9](https://github.com/hectorvent/floci/commit/38bdaf9616bdb833dbe1b8d4f13c30659b390768))

## [1.0.8](https://github.com/hectorvent/floci/compare/1.0.7...1.0.8) (2026-03-24)


### Bug Fixes

* return NoSuchVersion error for non-existent versionId ([5576222](https://github.com/hectorvent/floci/commit/557622299951b50c795204503ef727b8dac9b6b8))

## [1.0.7](https://github.com/hectorvent/floci/compare/1.0.6...1.0.7) (2026-03-24)


### Bug Fixes

* s3 unit test error ([0d77526](https://github.com/hectorvent/floci/commit/0d77526e2e457e8827ce82042dc5854d62794fde))

## [1.0.6](https://github.com/hectorvent/floci/compare/1.0.5...1.0.6) (2026-03-24)


### Bug Fixes

* **s3:** truncate LastModified timestamps to second precision ([#24](https://github.com/hectorvent/floci/issues/24)) ([ad31e7a](https://github.com/hectorvent/floci/commit/ad31e7a7b7ed8850ba668f7f09c3cad6dc8c81b0))

## [1.0.5](https://github.com/hectorvent/floci/compare/1.0.4...1.0.5) (2026-03-23)


### Bug Fixes

* fix s3 createbucket response format for rust sdk compatibility ([#11](https://github.com/hectorvent/floci/issues/11)) ([0e29c65](https://github.com/hectorvent/floci/commit/0e29c65266e55f48118ec00a4e6971d6264b08f2))

## [1.0.4](https://github.com/hectorvent/floci/compare/1.0.3...1.0.4) (2026-03-20)


### Bug Fixes

* adding build for development branch ([ae15f4c](https://github.com/hectorvent/floci/commit/ae15f4c79c7a28ba50c0426be5cdf1446cdafdeb))
* adding build for development branch ([95cb2aa](https://github.com/hectorvent/floci/commit/95cb2aaf1e63cf3a8b359bd5ab1c0b9f6cfb506b))
* docker build on native ([525f106](https://github.com/hectorvent/floci/commit/525f106eb4d302192d128a2ee00a80adbcb12c67))
* rename github action ([1fe1f6b](https://github.com/hectorvent/floci/commit/1fe1f6b7d87aa25573f015e2483b1c98a5962c4a))
* update workflow to download artifact into target ([4c18934](https://github.com/hectorvent/floci/commit/4c1893459579a6e5e1fa37145ace2a8433cd56e2))

## [1.0.4-dev.3](https://github.com/hectorvent/floci/compare/1.0.4-dev.2...1.0.4-dev.3) (2026-03-17)


### Bug Fixes

* update workflow to download artifact into target ([4c18934](https://github.com/hectorvent/floci/commit/4c1893459579a6e5e1fa37145ace2a8433cd56e2))

## [1.0.4-dev.2](https://github.com/hectorvent/floci/compare/1.0.4-dev.1...1.0.4-dev.2) (2026-03-17)


### Bug Fixes

* rename github action ([1fe1f6b](https://github.com/hectorvent/floci/commit/1fe1f6b7d87aa25573f015e2483b1c98a5962c4a))

## [1.0.4-dev.1](https://github.com/hectorvent/floci/compare/1.0.3...1.0.4-dev.1) (2026-03-17)


### Bug Fixes

* adding build for development branch ([ae15f4c](https://github.com/hectorvent/floci/commit/ae15f4c79c7a28ba50c0426be5cdf1446cdafdeb))
* adding build for development branch ([95cb2aa](https://github.com/hectorvent/floci/commit/95cb2aaf1e63cf3a8b359bd5ab1c0b9f6cfb506b))
* docker build on native ([525f106](https://github.com/hectorvent/floci/commit/525f106eb4d302192d128a2ee00a80adbcb12c67))

## [1.0.3-dev.1](https://github.com/hectorvent/floci/compare/1.0.2...1.0.3-dev.1) (2026-03-17)


### Bug Fixes

* adding build for development branch ([ae15f4c](https://github.com/hectorvent/floci/commit/ae15f4c79c7a28ba50c0426be5cdf1446cdafdeb))
* adding build for development branch ([95cb2aa](https://github.com/hectorvent/floci/commit/95cb2aaf1e63cf3a8b359bd5ab1c0b9f6cfb506b))
* improving native image compilation time ([49c69db](https://github.com/hectorvent/floci/commit/49c69db32314f7e2f94114d86d50e88b3e2a3884))
* update git pages config for the docs ([286bef9](https://github.com/hectorvent/floci/commit/286bef9dd7bfcf162f2ca5c2c030ea280e0b6de6))

## [1.0.2](https://github.com/hectorvent/floci/compare/1.0.1...1.0.2) (2026-03-15)


### Bug Fixes

* docker built action not being triggered ([a6b078f](https://github.com/hectorvent/floci/commit/a6b078fd76f973305ccab2e1ce6b45795e76b9b3))

## [1.0.1](https://github.com/hectorvent/floci/compare/1.0.0...1.0.1) (2026-03-15)


### Bug Fixes

* github action trigger ([156ceb2](https://github.com/hectorvent/floci/commit/156ceb2d884391864a24787e01b2c64b15b5f0f3))

# 1.0.0 (2026-03-15)


### Bug Fixes

* trigger build actions ([e96cf42](https://github.com/hectorvent/floci/commit/e96cf4212b187ef631116fe32b28b8be561056c1))
