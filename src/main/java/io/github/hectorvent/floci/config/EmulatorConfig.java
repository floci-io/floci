package io.github.hectorvent.floci.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

@ConfigMapping(prefix = "floci")
public interface EmulatorConfig {

    @WithDefault("4566")
    int port();

    @WithDefault("http://localhost:4566")
    String baseUrl();

    /**
     * When set, overrides the hostname in base-url for URLs returned in API responses
     * (e.g. SQS QueueUrl, SNS TopicArn). This is needed in multi-container Docker setups
     * where "localhost" in the response URL would resolve to the wrong container.
     *
     * Example: FLOCI_HOSTNAME=floci makes SQS return
     * http://floci:4566/000000000000/my-queue instead of http://localhost:4566/...
     *
     * Equivalent to LocalStack's LOCALSTACK_HOSTNAME.
     */
    Optional<String> hostname();

    /**
     * Returns the effective base URL, taking hostname and TLS into account.
     * If hostname is set, replaces the host in baseUrl with it.
     * If TLS is enabled, switches the scheme from http:// to https://.
     */
    default String effectiveBaseUrl() {
        String url = hostname()
                .map(h -> baseUrl().replaceFirst("://[^:/]+(:\\d+)?", "://" + h + "$1"))
                .orElse(baseUrl());
        if (tls().enabled() && url.startsWith("http://")) {
            url = "https://" + url.substring(7);
        }
        return url;
    }

    @WithDefault("us-east-1")
    String defaultRegion();

    @WithDefault("us-east-1a")
    String defaultAvailabilityZone();

    @WithDefault("000000000000")
    String defaultAccountId();

    @WithDefault("2048")
    int maxRequestSize();

    @WithDefault("public.ecr.aws")
    String ecrBaseUri();

    StorageConfig storage();

    DnsConfig dns();

    AuthConfig auth();

    SecurityConfig security();

    ServicesConfig services();

    DockerConfig docker();

    InitHooksConfig initHooks();

    TlsConfig tls();

    ProtocolsConfig protocols();

    interface ProtocolsConfig {
        /**
         * When enabled, requests carrying an RPC protocol signal that no
         * supported wire protocol claims are rejected per the Smithy
         * wire-protocol-selection guide (e.g. an unknown Smithy-Protocol header
         * value, a recognized-but-unimplemented rpc-v2-json request, or an
         * X-Amz-Target post with a foreign content type). When disabled such
         * requests are only logged and pass through to JAX-RS matching.
         */
        @WithDefault("false")
        boolean strictClaiming();

        /**
         * When enabled, a REST request whose SigV4 credential scope names a service
         * absent from the catalog is rejected with {@code UnknownOperationException}
         * instead of falling through JAX-RS matching into S3's path-style routes,
         * where it surfaces as a misleading {@code NoSuchBucket} (issue #1754).
         *
         * <p>On by default. Turn it off if Floci serves a route whose signing scope
         * is not yet enumerated in the catalog: the request then falls through as it
         * did before, rather than failing with a 404 that has no workaround.
         */
        @WithDefault("true")
        boolean rejectUnknownServiceScope();
    }

    interface DnsConfig {
        /**
         * Additional hostname suffixes the embedded DNS server will resolve to Floci's
         * container IP, alongside the primary {@code floci.hostname}.
         *
         * Useful for migrating from LocalStack without changing Lambda endpoint configuration:
         * <pre>
         * floci:
         *   dns:
         *     extra-suffixes:
         *       - localhost.localstack.cloud
         * </pre>
         *
         * Via environment variable (comma-separated for multiple values):
         * <pre>
         * FLOCI_DNS_EXTRA_SUFFIXES=localhost.localstack.cloud,localhost.example.internal
         * </pre>
         */
        Optional<List<String>> extraSuffixes();

        /**
         * When {@code true} (default), the configured {@link #containerFallbackServers()} are
         * appended after Floci's embedded DNS to every spawned container's {@code HostConfig.Dns}.
         * This gives Lambda/CodeBuild/etc. a real secondary resolver so public hostnames still
         * resolve if Floci's embedded forwarder cannot answer — mirroring the
         * {@code docker run --dns <FlociIP> --dns 8.8.8.8} workaround.
         *
         * <p>Disable (via {@code FLOCI_DNS_CONTAINER_FALLBACK_ENABLED=false}) in offline or
         * locked-down networks where the public resolvers are unreachable/blocked.
         */
        @WithDefault("true")
        boolean containerFallbackEnabled();

        /**
         * Ordered list of public DNS resolvers used both as the fallback upstream for Floci's
         * embedded DNS forwarder and (when {@link #containerFallbackEnabled()}) as the secondary
         * resolvers injected into spawned containers.
         *
         * <p>Via environment variable (comma-separated):
         * <pre>
         * FLOCI_DNS_CONTAINER_FALLBACK_SERVERS=1.1.1.1,1.0.0.1
         * </pre>
         */
        @WithDefault("8.8.8.8,8.8.4.4")
        List<String> containerFallbackServers();
    }

    interface SecurityConfig {
        Optional<List<String>> extraCorsAllowedOrigins();
        Optional<List<String>> extraCorsAllowedHeaders();
        Optional<List<String>> extraCorsExposeHeaders();

        @WithDefault("false")
        boolean disableCorsHeaders();

        /**
         * Whether to grant Private Network Access preflights (respond with
         * {@code Access-Control-Allow-Private-Network: true}) when the browser asks.
         * Only takes effect after the origin already passes the CORS allow-list, so a
         * page served from a public/secure origin can reach this loopback backend.
         *
         * <p>Off by default: it lets a public origin reach the private network, so it
         * must be opted into explicitly.</p>
         */
        @WithDefault("false")
        boolean corsAllowPrivateNetwork();
    }

    interface StorageConfig {
        @WithDefault("hybrid")
        String mode();

        @WithDefault("./data")
        String persistentPath();

        /** The path on the host machine where data is stored. Useful for Docker-in-Docker. */
        @WithDefault("${floci.storage.persistent-path}")
        String hostPersistentPath();

        /**
         * When {@code true}, named volumes are removed immediately after a child container stops
         * on resource delete. In {@code memory} storage mode volumes are always removed regardless
         * of this flag. Defaults to {@code false} to match real AWS behaviour (data survives delete).
         */
        @WithDefault("false")
        boolean pruneVolumesOnDelete();

        WalConfig wal();

        ServiceStorageOverrides services();

        EfsSharingConfig efs();
    }

    /**
     * Emulates an Amazon EFS access point's POSIX ownership for the shared local Docker volumes
     * that stand in for EFS file systems. A Docker named volume is created {@code root:root 0755},
     * so a container whose image runs as a non-root {@code USER} (as ECS tasks and
     * access-point-mounted workloads typically do) then cannot create files on it. Real EFS
     * applies the access point's {@code PosixUser} to all I/O and initialises the root directory
     * per {@code RootDirectory.CreationInfo}. These settings reproduce that: Floci initialises the
     * shared volume root's owner/permissions once and can run the mounting containers under a
     * fixed identity, so the emulated file system is writable by the intended uid/gid.
     *
     * <p>Every value is empty/false by default, so a shared volume behaves exactly as before —
     * a plain named volume with no ownership change — unless explicitly configured.
     */
    interface EfsSharingConfig {
        /** Owner uid applied to the volume root (EFS {@code RootDirectory.CreationInfo.OwnerUid}). */
        OptionalInt ownerUid();

        /** Owner gid applied to the volume root (EFS {@code RootDirectory.CreationInfo.OwnerGid}). */
        OptionalInt ownerGid();

        /**
         * Octal permissions applied to the volume root (EFS {@code RootDirectory.CreationInfo.Permissions}),
         * e.g. {@code "0777"}. A 4-digit value carries the special bits exactly as AWS does — e.g.
         * {@code "2775"} sets the setgid bit so subdirectories inherit the owner gid (the standard
         * POSIX pattern for a group-shared tree). When empty, no {@code chmod} for the permission
         * bits is performed; however the init helper container still runs if {@link #ownerUid()} or
         * {@link #ownerGid()} is set. Volume-root initialisation is skipped entirely only when all of
         * {@code owner-uid}, {@code owner-gid}, and {@code root-permissions} are left at their
         * defaults (plain named volume).
         */
        Optional<String> rootPermissions();

        /** Lightweight image used for the one-off {@code chown}/{@code chmod} of the volume root. */
        @WithDefault("busybox:stable")
        String initImage();

        /**
         * Run containers that mount a shared volume as this {@code "uid[:gid]"}, emulating the
         * access point's {@code PosixUser} that squashes all file-system I/O to a fixed identity.
         * Empty leaves the container's image {@code USER} in effect.
         */
        Optional<String> mountUser();

        /**
         * Supplementary group id added to containers that mount a shared volume, so a process
         * running under a different primary uid can still write a group-shared tree owned by
         * {@link #ownerGid()}. Empty adds no supplementary group.
         */
        OptionalInt mountGroupAdd();
    }

    interface ServiceStorageOverrides {
        SsmStorageConfig ssm();
        SqsStorageConfig sqs();
        S3StorageConfig s3();
        DynamoDbStorageConfig dynamodb();
        SnsStorageConfig sns();
        LambdaStorageConfig lambda();
        CloudWatchLogsStorageConfig cloudwatchlogs();
        CloudWatchMetricsStorageConfig cloudwatchmetrics();
        SecretsManagerStorageConfig secretsmanager();
        AcmStorageConfig acm();
        OpenSearchStorageConfig opensearch();
        AppConfigStorageConfig appconfig();
        AppConfigDataStorageConfig appconfigdata();
        ElastiCacheStorageConfig elasticache();
        MemoryDbStorageConfig memorydb();
        RdsStorageConfig rds();
        Ec2StorageConfig ec2();
        NeptuneStorageConfig neptune();
        BackupStorageConfig backup();
        FisStorageConfig fis();
        CloudFrontStorageConfig cloudfront();
        ResourceExplorer2StorageConfig resourceexplorer2();
        AppSyncStorageConfig appsync();
        BatchStorageConfig batch();
        LightsailStorageConfig lightsail();
        CodePipelineStorageConfig codepipeline();
        S3VectorsStorageConfig s3vectors();
        S3TablesStorageConfig s3tables();
        EcsStorageConfig ecs();
        CodeBuildStorageConfig codebuild();
        ConfigStorageConfig config();
        CodeDeployStorageConfig codedeploy();
        TranscribeStorageConfig transcribe();
        TaggingStorageConfig tagging();
        ElasticBeanstalkStorageConfig elasticbeanstalk();
        CloudTrailStorageConfig cloudtrail();
        RumStorageConfig rum();
        GuardDutyStorageConfig guardduty();
        EmrServerlessStorageConfig emrserverless();

        ControlTowerStorageConfig controltower();

        ApsStorageConfig aps();

        LakeFormationStorageConfig lakeformation();
        EfsStorageConfig efs();
    }

    interface ApsStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface SsmStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface SqsStorageConfig {
        Optional<String> mode();
    }

    interface S3StorageConfig {
        Optional<String> mode();
    }

    interface DynamoDbStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface SnsStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface LambdaStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CloudWatchLogsStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CloudWatchMetricsStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface SecretsManagerStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface AcmStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface OpenSearchStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface AppConfigStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface AppConfigDataStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface ElastiCacheStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface MemoryDbStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface RdsStorageConfig {
        Optional<String> mode();
    }

    interface Ec2StorageConfig {
        Optional<String> mode();
    }

    interface NeptuneStorageConfig {
        Optional<String> mode();
    }

    interface BackupStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface FisStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CloudFrontStorageConfig {
        Optional<String> mode();
    }

    interface ResourceExplorer2StorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface AppSyncStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface BatchStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface LightsailStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CodePipelineStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface S3VectorsStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface S3TablesStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface EcsStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CodeBuildStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface ConfigStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface TranscribeStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface TaggingStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface ElasticBeanstalkStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CloudTrailStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface RumStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface ControlTowerStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface GuardDutyStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface EmrServerlessStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface LakeFormationStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }
    interface EfsStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }
  
    interface CodeDeployStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface WalConfig {
        @WithDefault("30000")
        long compactionIntervalMs();
    }

    interface AuthConfig {
        @WithDefault("false")
        boolean validateSignatures();

        @WithDefault("local-emulator-secret")
        String presignSecret();
    }

    interface ServicesConfig {
        /** Shared Docker network for all container-based services (Lambda, RDS, ElastiCache).
         *  Per-service dockerNetwork settings override this value when present. */
        Optional<String> dockerNetwork();

        SsmServiceConfig ssm();
        SqsServiceConfig sqs();
        S3ServiceConfig s3();
        DynamoDbServiceConfig dynamodb();
        SnsServiceConfig sns();
        LambdaServiceConfig lambda();
        ApiGatewayServiceConfig apigateway();
        IamServiceConfig iam();
        MskServiceConfig msk();
        AmazonMqServiceConfig amazonmq();
        KinesisAnalyticsServiceConfig kinesisAnalytics();
        ElastiCacheServiceConfig elasticache();
        MemoryDbServiceConfig memorydb();
        RdsServiceConfig rds();
        RdsDataServiceConfig rdsData();
        EventBridgeServiceConfig eventbridge();
        CloudMapServiceConfig cloudmap();
        EmrServiceConfig emr();
        WafV2ServiceConfig wafv2();
        SchedulerServiceConfig scheduler();
        CloudWatchLogsServiceConfig cloudwatchlogs();
        CloudWatchMetricsServiceConfig cloudwatchmetrics();
        SecretsManagerServiceConfig secretsmanager();
        ApiGatewayV2ServiceConfig apigatewayv2();
        KinesisServiceConfig kinesis();
        FirehoseServiceConfig firehose();
        KmsServiceConfig kms();
        CognitoServiceConfig cognito();
        StepFunctionsServiceConfig stepfunctions();
        SwfServiceConfig swf();
        CloudFormationServiceConfig cloudformation();
        AcmServiceConfig acm();
        AthenaServiceConfig athena();
        GlueServiceConfig glue();
        SesServiceConfig ses();
        OpenSearchServiceConfig opensearch();
        Ec2ServiceConfig ec2();
        EcsServiceConfig ecs();
        AppConfigServiceConfig appconfig();
        AppConfigDataServiceConfig appconfigdata();
        EcrServiceConfig ecr();
        ResourceGroupsTaggingServiceConfig tagging();
        BedrockRuntimeServiceConfig bedrockRuntime();
        EksServiceConfig eks();
        MwaaServiceConfig mwaa();
        PipesServiceConfig pipes();
        BedrockAgentCoreControlServiceConfig bedrockAgentCoreControl();
        BedrockAgentCoreServiceConfig bedrockAgentCore();
        ElbV2ServiceConfig elbv2();
        CodeBuildServiceConfig codebuild();
        CodeDeployServiceConfig codedeploy();
        CodePipelineServiceConfig codepipeline();
        AutoScalingServiceConfig autoscaling();
        ApplicationAutoScalingServiceConfig applicationautoscaling();
        ElasticBeanstalkServiceConfig elasticbeanstalk();
        BackupServiceConfig backup();
        FisServiceConfig fis();
        NeptuneServiceConfig neptune();
        DocDbServiceConfig docdb();
        Route53ServiceConfig route53();
        TransferServiceConfig transfer();
        TextractServiceConfig textract();
        ComprehendServiceConfig comprehend();
        PricingServiceConfig pricing();
        DuckConfig duck();
        TranscribeServiceConfig transcribe();
        CostExplorerServiceConfig ce();
        CurServiceConfig cur();
        BcmDataExportsServiceConfig bcmDataExports();
        ConfigServiceConfig configservice();
        CloudTrailServiceConfig cloudtrail();
        CloudControlServiceConfig cloudcontrol();
        ResourceExplorer2ServiceConfig resourceexplorer2();
        CloudFrontServiceConfig cloudfront();
        AppSyncServiceConfig appsync();
        BatchServiceConfig batch();
        LightsailServiceConfig lightsail();
        UiServiceConfig ui();
        S3VectorsServiceConfig s3vectors();
        S3TablesServiceConfig s3tables();
        IotServiceConfig iot();
        IotDataServiceConfig iotdata();
        CloudHsmV2ServiceConfig cloudhsmv2();
        OrganizationsServiceConfig organizations();
        RumServiceConfig rum();
        GuardDutyServiceConfig guardduty();
        EmrServerlessServiceConfig emrserverless();
        Route53ResolverServiceConfig route53resolver();
        NetworkFirewallServiceConfig networkfirewall();
        ServiceCatalogServiceConfig servicecatalog();
        SsoAdminServiceConfig ssoadmin();
        ServiceQuotasServiceConfig servicequotas();
        RamServiceConfig ram();
        ControlTowerServiceConfig controltower();

        ApsServiceConfig aps();

        LakeFormationServiceConfig lakeformation();
        EfsServiceConfig efs();
    }

    interface SsoAdminServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ApsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface IotServiceConfig {
        @WithDefault("true")
        boolean enabled();

        MqttConfig mqtt();
    }

    interface MqttConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean autoStart();

        @WithDefault("0.0.0.0")
        String host();

        @WithDefault("1883")
        int port();
    }

    interface IotDataServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface RumServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ControlTowerServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface GuardDutyServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface EmrServerlessServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface Route53ResolverServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface NetworkFirewallServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ServiceCatalogServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ServiceQuotasServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface RamServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface LakeFormationServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }
  
    interface EfsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface LightsailServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudControlServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }
    interface S3VectorsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface S3TablesServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface TransferServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface BackupServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("3")
        int jobCompletionDelaySeconds();
    }

    interface FisServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface Route53ServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("ns-1.awsdns-01.org")
        String defaultNameserver1();

        @WithDefault("ns-2.awsdns-02.net")
        String defaultNameserver2();

        @WithDefault("ns-3.awsdns-03.com")
        String defaultNameserver3();

        @WithDefault("ns-4.awsdns-04.co.uk")
        String defaultNameserver4();
    }

    interface ConfigServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudTrailServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** How often the writer flushes pending records into the destination
         *  bucket. Real AWS delivers data events with ~5-minute lag; the
         *  default here is 60s so dev/CI feedback loops stay fast. */
        @WithDefault("60")
        int flushIntervalSeconds();
    }

    interface ResourceExplorer2ServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface AutoScalingServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ApplicationAutoScalingServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ElasticBeanstalkServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CodeBuildServiceConfig {
        @WithDefault("true")
        boolean enabled();

        Optional<String> dockerNetwork();
    }

    interface BatchServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("immediate")
        String runnerMode();

        Optional<String> dockerNetwork();
    }

    interface CodeDeployServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CodePipelineServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SsmServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("5")
        int maxParameterHistory();
    }

    interface SqsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("30")
        int defaultVisibilityTimeout();

        @WithDefault("1048576")
        int maxMessageSize();

        @WithDefault("false")
        boolean clearFifoDeduplicationCacheOnPurge();
    }

    interface S3ServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean enforceAuth();

        @WithDefault("3600")
        int defaultPresignExpirySeconds();

        /**
         * When true, S3 bucket/object existence resolution spans every account's partition,
         * modelling AWS's globally-unique bucket namespace (a bucket owned by one account is
         * reachable cross-account). Listing (ListBuckets/ListObjects) stays owner-scoped.
         * Default false preserves per-account bucket isolation.
         *
         * <p><strong>Why this widening is deliberate, and what it costs.</strong> Floci's default
         * is to partition every resource by the caller's account, which is the right model for
         * services whose names are account-scoped in AWS. S3 bucket names are not: they are unique
         * across all accounts and partitions, a bucket lives in exactly one owning account, and
         * whether another account may touch it is decided by policy, not by the name being
         * unreachable. Emulating that with a hard per-account partition makes two accounts able to
         * hold different buckets under one name — a state AWS cannot reach — and makes a legitimate
         * cross-account call (LZA's central logging bucket, a custom-resource Lambda calling back
         * under the management account) fail with {@code NoSuchBucket} instead of being authorized
         * or denied on its merits. This flag opts into the AWS-faithful namespace instead.
         *
         * <p>The trade-off is that account partitioning stops being a second, implicit line of
         * defence for S3: with the flag on, a caller that names another account's bucket resolves
         * it, and only IAM/bucket-policy evaluation stands between the caller and the object —
         * exactly as in AWS, but stricter than Floci's default elsewhere. Mutations are written
         * back to the bucket's <em>owning</em> account rather than forked into the caller's, and
         * listing stays owner-scoped, so the flag never reassigns ownership or leaks a bucket
         * inventory. It is off by default: turn it on when emulating a multi-account estate whose
         * real behaviour depends on the global namespace, and leave it off when the per-account
         * partition is the isolation you are relying on. Account-scoped S3 state — notably the
         * account-level Block Public Access configuration — is never widened by this flag.
         */
        @WithDefault("false")
        boolean globalBucketNamespace();
    }

    interface DynamoDbServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SnsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ApiGatewayServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface IamServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean enforcementEnabled();

        @WithDefault("false")
        boolean seedDeployerPrincipal();

        /**
         * Alias to seed for the default account at startup, so callers that read the account
         * alias find one without creating it first. Unset means the account has no alias, which
         * is the AWS default.
         */
        Optional<String> accountAlias();
    }

    interface MskServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("redpandadata/redpanda:latest")
        String defaultImage();

        @WithDefault("9300")
        int kafkaHostPortBase();

        @WithDefault("9399")
        int kafkaHostPortMax();
    }

    interface AmazonMqServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("rabbitmq:3-management")
        String defaultImage();
    }

    interface KinesisAnalyticsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** When true, StartApplication comes up RUNNING immediately with no backing Flink
         *  container. Useful for tests and hosts without a Docker daemon. */
        @WithDefault("false")
        boolean mock();

        /**
         * Optional fixed image used for every application regardless of the requested
         * {@code RuntimeEnvironment} (private registry mirror, pinned patch). When unset, the image
         * is chosen from the runtime via {@code KinesisAnalyticsRuntimes.imageFor(runtimeEnvironment)}.
         */
        Optional<String> defaultImage();
    }

    interface ElastiCacheServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("6379")
        int proxyBasePort();

        @WithDefault("6399")
        int proxyMaxPort();

        @WithDefault("valkey/valkey:8")
        String defaultImage();

        @WithDefault("memcached:1.6")
        String defaultMemcachedImage();

        /** Docker network to attach ElastiCache containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();

        /**
         * Hostname cluster-mode nodes announce in MOVED/ASK redirects and cluster topology,
         * and that is reported as the ConfigurationEndpoint. Must resolve to Floci from every
         * client location; set it when the main hostname only resolves inside Floci's Docker
         * network. Empty = the main hostname.
         */
        Optional<String> clusterAnnounceHostname();
    }

    interface MemoryDbServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("6400")
        int proxyBasePort();

        @WithDefault("6419")
        int proxyMaxPort();

        @WithDefault("valkey/valkey:8")
        String defaultImage();

        /** Docker network to attach MemoryDB containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();
    }

    interface RdsServiceConfig {
        String DEFAULT_POSTGRES_IMAGE = "postgres:16-alpine";
        String DEFAULT_MYSQL_IMAGE = "mysql:8.0";
        String DEFAULT_MARIADB_IMAGE = "mariadb:11";

        @WithDefault("true")
        boolean enabled();

        /** When true, DB clusters and instances are created instantly without a real Docker
         *  container or auth proxy (API/metadata only). Useful for CI and environments without
         *  access to the Docker socket. */
        @WithDefault("false")
        boolean mock();

        @WithDefault("7000")
        int proxyBasePort();

        @WithDefault("7099")
        int proxyMaxPort();

        /** Empty when Floci should adapt its built-in image to the requested engine version. */
        Optional<String> defaultPostgresImage();

        /** Empty when Floci should adapt its built-in image to the requested engine version. */
        Optional<String> defaultMysqlImage();

        /** Empty when Floci should adapt its built-in image to the requested engine version. */
        Optional<String> defaultMariadbImage();

        /** Hostname advertised for RDS endpoints. Uses published Docker ports when configured. */
        Optional<String> endpointHost();

        /** Docker network to attach DB containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();
    }

    interface RdsDataServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("180")
        long transactionTtlSeconds();
    }

    interface NeptuneServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** Base port of the proxy port range. First cluster gets this port. */
        @WithDefault("8182")
        int proxyBasePort();

        /** Inclusive upper bound of the proxy port range. */
        @WithDefault("8282")
        int proxyMaxPort();

        /**
         * Backend graph engine and query language: {@code gremlin} (Apache TinkerPop, Gremlin
         * over WebSocket) or {@code neo4j} (Neo4j, openCypher over Bolt). Mirrors LocalStack's
         * {@code NEPTUNE_DB_TYPE}.
         */
        @WithDefault("gremlin")
        String dbType();

        /** Image used when {@code db-type=gremlin}. */
        @WithDefault("tinkerpop/gremlin-server:3.7.3")
        String defaultImage();

        /** Image used when {@code db-type=neo4j} (openCypher / Bolt). */
        @WithDefault("neo4j:5-community")
        String defaultNeo4jImage();

        Optional<String> dockerNetwork();
    }

    interface DocDbServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("mongo:7.0")
        String defaultImage();

        Optional<String> dockerNetwork();
    }

    interface EventBridgeServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudMapServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** Delay before an async operation (CreateNamespace, RegisterInstance, …)
         *  transitions from PENDING to SUCCESS. 0 = complete immediately. */
        @WithDefault("0")
        int operationCompletionDelaySeconds();
    }

    interface EmrServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("emr-7.5.0")
        String defaultReleaseLabel();

        /** Delay before a cluster reaches WAITING; 0 = advance synchronously. */
        @WithDefault("0")
        int clusterStartupDelaySeconds();
    }

    interface WafV2ServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SchedulerServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * Run the background dispatcher that fires schedule targets. Setting this
         * to {@code false} keeps the scheduler API CRUD-only (the pre-invocation
         * behavior). Invocation is only attempted when the service itself is enabled.
         */
        @WithDefault("true")
        boolean invocationEnabled();

        /**
         * How often the dispatcher scans for due schedules. Must be >= 1s;
         * default 10s is a reasonable trade-off between latency and load for local use.
         */
        @WithDefault("10")
        long tickIntervalSeconds();
    }

    interface CloudWatchLogsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("10000")
        int maxEventsPerQuery();

        /**
         * Artificial Logs Insights query completion delay, in milliseconds. With the default 0,
         * queries complete immediately (fast local dev). A positive value emulates the real
         * asynchronous lifecycle — StartQuery → Running → Complete after this delay — which also
         * makes StopQuery on a still-running query return {@code success=true}.
         */
        @WithDefault("0")
        long queryCompletionDelayMs();
    }

    interface CloudWatchMetricsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SecretsManagerServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("30")
        int defaultRecoveryWindowDays();
    }

    interface ApiGatewayV2ServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface KinesisServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface FirehoseServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * How often the buffer flusher checks for streams whose buffering
         * interval (BufferingHints.IntervalInSeconds) has elapsed.
         */
        @WithDefault("10")
        long tickIntervalSeconds();

        /**
         * Emulator-only volume trigger: number of buffered records that forces
         * an immediate flush, complementing the stream's BufferingHints.
         * Disabled by default (0) so out-of-the-box delivery matches real AWS;
         * set to 1 for LocalStack-style record-at-a-time delivery in local dev.
         */
        @WithDefault("0")
        int flushRecordCount();
    }

    interface KmsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CognitoServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface StepFunctionsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** Allows invoking plain HTTP endpoints. By default, AWS only allows HTTPS. */
        @WithDefault("true")
        boolean allowPlaintextHttp();

        /**
         * Path to a Step Functions Local compatible mock configuration file
         * ({@code MockConfigFile.json}). When set, {@code StartExecution} on
         * {@code <stateMachineArn>#<testCaseName>} runs the state machine with that test
         * case's mocked service integration responses. The main application.yml defaults
         * this to the {@code SFN_MOCK_CONFIG} environment variable for drop-in
         * compatibility with Step Functions Local.
         */
        Optional<String> mockConfigFile();
    }

    interface SwfServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * Run the background sweep that expires activity, decision, workflow and timer
         * timeouts. Setting this to {@code false} leaves timeouts recorded but never
         * fired, which is useful for tests that drive the clock themselves.
         */
        @WithDefault("true")
        boolean timeoutSweepEnabled();

        /**
         * How often the timeout sweep runs. SWF timeouts are specified in whole seconds,
         * so a 1s sweep bounds the observable lateness of an expiry at one second.
         */
        @WithDefault("1")
        long timeoutSweepIntervalSeconds();
    }

    interface CloudFormationServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("30")
        long deletedStackRetentionSeconds();

        /**
         * Whether an {@code AWS::Lambda::Function} whose template names code in S3 that cannot be
         * read should fall back to the built-in stub handler instead of failing the resource.
         *
         * <p>Defaults to {@code false}, matching real CloudFormation, which fails the resource and
         * rolls the stack back. Set to {@code true} to restore the older behaviour for a stack that
         * deliberately leaves Lambda packages unbuilt — note that such a stack reports
         * {@code CREATE_COMPLETE} while serving a placeholder that returns
         * {@code {"statusCode":200}}, so it cannot be used to verify the real function.
         */
        @WithDefault("false")
        boolean allowStubLambdaCode();
    }

    interface AcmServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** Seconds to wait before transitioning from PENDING_VALIDATION to ISSUED (0 = immediate) */
        @WithDefault("0")
        int validationWaitSeconds();
    }

    interface CloudHsmV2ServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface OrganizationsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean scpEnforcementEnabled();
    }

    interface AthenaServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();
    }

    interface DuckConfig {
        /** When set, Floci uses this URL and skips floci-duck container management. */
        Optional<String> url();

        @WithDefault("floci/floci-duck:latest")
        String defaultImage();
    }

    interface GlueServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SesServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** SMTP server host for email relay. Empty = relay disabled (emails stored only). */
        Optional<String> smtpHost();

        /** SMTP server port. */
        @WithDefault("25")
        int smtpPort();

        /** SMTP authentication username. Empty = no authentication. */
        Optional<String> smtpUser();

        /** SMTP authentication password. */
        Optional<String> smtpPass();

        /** STARTTLS mode: DISABLED, OPTIONAL, or REQUIRED. */
        @WithDefault("DISABLED")
        String smtpStarttls();
    }

    interface OpenSearchServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** When true, domains are simulated in-memory without real Docker containers. */
        @WithDefault("false")
        boolean mock();

        /**
         * Optional fixed image used for every domain regardless of the
         * requested {@code EngineVersion}. Useful for operators running a
         * private registry mirror or pinning a specific patch tag. When unset
         * (the common case), images resolve from
         * {@code OpenSearchVersions.imageFor(...)} per the requested version.
         */
        Optional<String> defaultImage();

        @WithDefault("9400")
        int proxyBasePort();

        @WithDefault("9499")
        int proxyMaxPort();

        @WithDefault("${floci.storage.persistent-path}/opensearch")
        String dataPath();

        @WithDefault("false")
        boolean keepRunningOnShutdown();
    }

    interface EcsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** When true, tasks go straight to RUNNING without starting real Docker containers. */
        @WithDefault("false")
        boolean mock();

        Optional<String> dockerNetwork();

        @WithDefault("512")
        int defaultMemoryMb();

        @WithDefault("256")
        int defaultCpuUnits();
    }

    interface ResourceGroupsTaggingServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface BedrockRuntimeServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * Converse/InvokeModel backend: "stub" (default, hardcoded response, no
         * external calls) or "proxy" (forwards Converse to an OpenAI-compatible
         * /chat/completions endpoint; see {@link BedrockProxyConfig}).
         */
        @WithDefault("stub")
        String backend();

        BedrockProxyConfig proxy();
    }

    interface BedrockProxyConfig {
        /**
         * Base URL of the OpenAI-compatible backend (Ollama, OpenRouter, LiteLLM,
         * vLLM), e.g. "http://localhost:11434/v1". Required when backend=proxy;
         * requests are POSTed to "{url}/chat/completions".
         */
        Optional<String> url();

        /** Sent as "Authorization: Bearer {apiKey}" when present. */
        Optional<String> apiKey();

        /**
         * Fallback OpenAI-side model id used when no explicit mapping matches
         * and passthrough is disabled.
         */
        Optional<String> defaultModel();

        /**
         * Comma-separated {@code bedrockModelId=openaiModelId} pairs, e.g.
         * {@code "anthropic.claude-3-sonnet-20240229-v1:0=claude-3-sonnet"}.
         * A delimited string rather than a native Map config property: Bedrock
         * model ids contain '.' and ':', which collide with SmallRye's per-key
         * env-var naming convention for maps.
         */
        Optional<String> modelMapping();

        /**
         * When true, and no explicit mapping matches, forward the raw Bedrock
         * model id as-is instead of requiring a mapping or defaultModel.
         */
        @WithDefault("false")
        boolean passthrough();

        /**
         * How long to wait for the backend to finish generating a response before
         * failing the request with ModelTimeoutException. Larger models on
         * CPU-backed backends (e.g. Ollama) may need more than the default.
         */
        @WithDefault("60")
        int requestTimeoutSeconds();
    }

    interface TextractServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ComprehendServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface PricingServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * Filesystem directory overriding the bundled pricing snapshot. When set, files at
         * {@code <path>/services.json}, {@code <path>/products/<service>/<region>.json},
         * {@code <path>/attribute-values/<service>/<attribute>.json}, and
         * {@code <path>/price-lists/<service>.json} are read in preference to the classpath copy.
         */
        Optional<String> snapshotPath();
    }

    interface TranscribeServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CostExplorerServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * Synthetic monthly USD credit applied as a {@code Credit} {@code RECORD_TYPE}
         * row in {@code GetCostAndUsage} responses. The emitted credit is capped at
         * the synthesized monthly usage so net cost never goes below zero.
         * Defaults to zero (no credit emitted).
         */
        @WithDefault("0.0")
        double creditUsdMonthly();
    }

    interface CurServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * Controls when CUR Parquet artifacts are emitted:
         * <ul>
         *   <li>{@code synchronous} — emit on report definition mutations (default; suits tests)</li>
         *   <li>{@code daily} — emit once per 24h via the CUR-owned scheduled executor</li>
         *   <li>{@code off} — management plane only, no Parquet emission</li>
         * </ul>
         */
        @WithDefault("synchronous")
        String emitMode();

        /**
         * S3 bucket used to stage NDJSON row payloads before DuckDB writes the
         * final Parquet artifact. Created on first use if it doesn't exist.
         */
        @WithDefault("floci-cur-staging")
        String stagingBucket();
    }

    interface CloudFrontServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("cloudfront.net")
        String domainSuffix();

        /**
         * Exact custom-origin hostnames allowed to resolve to private or otherwise non-routable
         * addresses. Empty by default to match CloudFront's public custom-origin boundary.
         */
        Optional<List<String>> allowedPrivateOriginHosts();
    }

    interface AppSyncServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** Worker threads for async schema creation. Env: FLOCI_SERVICES_APPSYNC_SCHEMA_WORKER_THREADS */
        @WithDefault("4")
        int schemaWorkerThreads();

        /** Seconds to wait for in-flight schema workers on shutdown. Env: FLOCI_SERVICES_APPSYNC_SCHEMA_WORKER_SHUTDOWN_TIMEOUT_SECONDS */
        @WithDefault("30")
        int schemaWorkerShutdownTimeoutSeconds();
    }

    interface BcmDataExportsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * Same semantics as {@code floci.services.cur.emit-mode} but applied to
         * BCM Data Exports {@code Export} records.
         */
        @WithDefault("synchronous")
        String emitMode();
    }

    interface UiServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("floci/floci-ui:latest")
        String image();

        @WithDefault("floci-ui")
        String containerName();

        /** Single fixed host port the UI is published on (single-instance service). */
        @WithDefault("4500")
        int port();

        @WithDefault("false")
        boolean keepRunningOnShutdown();

        Optional<String> dockerNetwork();
    }

    interface EcrServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("registry:2")
        String registryImage();

        @WithDefault("floci-ecr-registry")
        String registryContainerName();

        @WithDefault("5100")
        int registryBasePort();

        @WithDefault("5199")
        int registryMaxPort();

        @WithDefault("${floci.storage.persistent-path}/ecr")
        String dataPath();

        @WithDefault("false")
        boolean tlsEnabled();

        @WithDefault("true")
        boolean keepRunningOnShutdown();

        /** URI style for repositoryUri responses: "hostname" (default, *.dkr.ecr.<region>.localhost) or "path". */
        @WithDefault("hostname")
        String uriStyle();

        Optional<String> dockerNetwork();
    }

    interface LambdaServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("128")
        int defaultMemoryMb();

        @WithDefault("3")
        int defaultTimeoutSeconds();

        Optional<String> dockerHostOverride();

        /**
         * Runtime API port pool. One port is held for the lifetime of each running Lambda
         * container, so this range is a hard ceiling on concurrent Lambda executions.
         *
         * <p>The former 9200-9299 default was exactly 100 ports, which cannot run a
         * multi-account landing zone — LZA's LoggingStack fans out ~8 custom-resource Lambdas
         * per account, so a 14-account org needs ~112 at once. Exhaustion did not read as a
         * capacity limit either: the custom resource reported FAILED and CloudFormation rolled
         * the stack back, so it surfaced as an unrelated CFN error (issue #2206).
         *
         * <p>12000-12499 rather than a widened 9200 range: 9200 is OpenSearch, 9092 Kafka,
         * 9644 the Redpanda admin port and 9400-9499 the ECS proxy pool. Those are
         * sibling-container-internal so they would not truly collide, but a pool this wide
         * stops being obvious about what it covers. 12000-12499 touches nothing.
         */
        @WithDefault("12000")
        int runtimeApiBasePort();

        @WithDefault("12499")
        int runtimeApiMaxPort();

        @WithDefault("./data/lambda-code")
        String codePath();

        @WithDefault("1000")
        long pollIntervalMs();

        @WithDefault("false")
        boolean ephemeral();

        @WithDefault("300")
        int containerIdleTimeoutSeconds();

        /** Docker network to attach Lambda containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();

        /**
         * Base name prefix for the containers and code volumes Lambda spawns, replacing the
         * default {@code floci} (e.g. prefix {@code acme} names containers
         * {@code acme-<function>-<id>} and code volumes {@code acme-code-<function>-<hash>}).
         * Must be a valid Docker name segment ({@code [A-Za-z0-9][A-Za-z0-9_.-]*}); invalid
         * values are ignored with a warning. Unset or blank falls back to {@code floci}.
         *
         * Env var: FLOCI_SERVICES_LAMBDA_CONTAINER_NAME_PREFIX
         */
        Optional<String> containerNamePrefix();

        /**
         * Extra /etc/hosts entries added to every Lambda container, as "hostname:ip" pairs.
         * The ip may be the literal "host-gateway" to map to the Docker host, mirroring
         * {@code docker run --add-host hostname:host-gateway}.
         *
         * Env var: FLOCI_SERVICES_LAMBDA_EXTRA_HOSTS (comma-separated)
         */
        Optional<List<String>> extraHosts();

        /**
         * Concurrent executions ceiling applied per region. AWS Lambda's
         * "account-level" concurrency is in fact a per-region quota (default 1000);
         * Floci mirrors that semantics and partitions counters by the region
         * segment of each function ARN.
         */
        @WithDefault("1000")
        int regionConcurrencyLimit();

        /**
         * Minimum unreserved concurrency that must remain after PutFunctionConcurrency,
         * matching AWS (100). Puts that would leave less than this are rejected.
         */
        @WithDefault("100")
        int unreservedConcurrencyMin();

        /**
         * Host path to bind-mount (read-only) into Lambda containers at /opt/aws-config.
         * When set, no AWS credential env vars are injected; instead
         * AWS_SHARED_CREDENTIALS_FILE and AWS_CONFIG_FILE are set to point at
         * the mounted files, ensuring SDK discovery works regardless of container HOME.
         * When absent, a function whose execution role exists in Floci receives temporary
         * credentials for that role. Functions with an unknown role retain the compatibility
         * fallback to Floci's own AWS credential environment or test/test/test.
         * Blank values are treated as absent.
         *
         * Env var: FLOCI_SERVICES_LAMBDA_AWS_CONFIG_PATH
         */
        Optional<String> awsConfigPath();

        /**
         * Execution backend for Lambda environments: {@code docker} (default) runs each
         * environment as a Docker container, {@code kubernetes} runs it as a pod in the
         * cluster Floci is configured against (in-cluster config or local kubeconfig).
         *
         * Env var: FLOCI_SERVICES_LAMBDA_EXECUTOR
         */
        @WithDefault("docker")
        String executor();

        KubernetesExecutor kubernetes();

        interface KubernetesExecutor {
            /**
             * Namespace Lambda pods are created in. Multiple Floci instances must use
             * separate namespaces — orphaned pods are swept by label on startup.
             *
             * Env var: FLOCI_SERVICES_LAMBDA_KUBERNETES_NAMESPACE
             */
            @WithDefault("default")
            String namespace();

            /**
             * Extra labels applied to Lambda pods, as {@code key=value} entries.
             *
             * Env var: FLOCI_SERVICES_LAMBDA_KUBERNETES_LABELS (comma-separated)
             */
            Optional<List<String>> labels();

            /**
             * Host or IP that Lambda pods use to reach Floci (the Runtime API port range
             * and the main port). When unset, Floci auto-detects its own pod address if
             * running in-cluster; when running outside the cluster this must be set to an
             * address the cluster's pods can reach.
             *
             * Env var: FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS
             */
            Optional<String> flociAddress();

            /**
             * Image for the init container that downloads and unpacks function code into
             * the pod. Must provide sh, wget and unzip.
             *
             * Env var: FLOCI_SERVICES_LAMBDA_KUBERNETES_INIT_IMAGE
             */
            @WithDefault("busybox:1.36")
            String initImage();
        }

        HotReload hotReload();

        interface HotReload {
            /**
             * When true, the magic bucket name {@code hot-reload} triggers a bind-mount of the
             * S3Key path (a Docker-host absolute path) into the Lambda container instead of
             * extracting a ZIP. Changes on disk are visible on the next invocation without
             * re-deploying. Disabled by default — when false, {@code hot-reload} is an
             * ordinary (non-existent) bucket and returns NoSuchBucket as usual.
             *
             * Env var: FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED
             */
            @WithDefault("false")
            boolean enabled();

            /**
             * Optional allow-list of absolute path prefixes. When non-empty, the S3Key supplied
             * to a hot-reload CreateFunction/UpdateFunctionCode must start with one of these
             * prefixes. Empty = all absolute paths are accepted.
             *
             * Env var: FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ALLOWED_PATHS
             */
            Optional<List<String>> allowedPaths();
        }
    }

    interface Ec2ServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /**
         * When true, DescribeInstances and IMDS report each instance's CFN- and
         * subnet-allocated private IP (AWS-faithful) instead of the Docker
         * container's bridge IP (#1983). Default false keeps the bridge IP as the
         * reported private address, which lets instances reach each other at that
         * address on the shared Docker network. Routing/IMDS always use the
         * container bridge IP regardless of this flag; only the reported
         * PrivateIpAddress changes.
         */
        @WithDefault("false")
        boolean awsFaithfulPrivateIp();

        /**
         * Whether an EC2 instance's Docker container IP is routable from the machines that
         * consume Floci's API responses (Terraform, Terratest, your shell). When true,
         * DescribeInstances / DescribeAddresses report the container IP, so the address they
         * hand out accepts connections on the service's real port (22 for SSH, and every other
         * port the guest listens on) with no port mapping involved. When false, Floci keeps
         * reporting 127.0.0.1 and reachability depends on the published high host ports.
         *
         * Unset (the default) means auto-detect: Floci opens a throwaway TCP connection towards
         * the container network and treats a refusal as proof of a route. Set it explicitly when
         * the probe cannot speak for your clients — most notably when Floci itself runs as a
         * container, where the probe measures container-to-container reachability rather than
         * host-to-container.
         *
         * Env var: FLOCI_SERVICES_EC2_CONTAINER_IPS_ROUTABLE
         */
        Optional<Boolean> containerIpsRoutable();

        /** Port on the Floci host for the IMDS HTTP server (169.254.169.254 equivalent). */
        @WithDefault("9169")
        int imdsPort();

        /** Lowest host port in the range published for EC2 instance SSH (port 22). */
        @WithDefault("2200")
        int sshPortRangeStart();

        /** Highest host port in the range published for EC2 instance SSH (port 22). */
        @WithDefault("2299")
        int sshPortRangeEnd();

        /**
         * When true, TCP ports opened by an instance's security-group ingress rules are
         * published on the host via a socat sidecar container, both at launch and on later
         * authorize-security-group-ingress. Set false to keep security groups as metadata only.
         */
        @WithDefault("true")
        boolean publishSecurityGroupPorts();

        /** Lowest host port in the range allocated for published security-group app ports. */
        @WithDefault("30000")
        int appPortRangeStart();

        /** Highest host port in the range allocated for published security-group app ports. */
        @WithDefault("30999")
        int appPortRangeEnd();

        /**
         * Upper bound on app ports published per instance. Also bounds any single ingress
         * rule's port span: wider ranges (e.g. an allow-all 0-65535 rule) are skipped so a
         * single rule cannot spawn thousands of socat sidecars or exhaust the host-port range.
         */
        @WithDefault("20")
        int maxPublishedPortsPerInstance();

        /** Image used for the socat sidecar that forwards published security-group ports. */
        @WithDefault("alpine/socat")
        String socatImage();

        /** When true, instances go straight to RUNNING without launching Docker containers. */
        @WithDefault("false")
        boolean mock();
    }

    interface AppConfigServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface AppConfigDataServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface PipesServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface BedrockAgentCoreControlServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface BedrockAgentCoreServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("{\"output\":\"yes\"}")
        String invokeResponse();

        @WithDefault("false")
        boolean validateRuntimeExists();
    }

    interface ElbV2ServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();
    }

    interface EksServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** When true, clusters go straight to ACTIVE without starting real Docker containers. */
        @WithDefault("false")
        boolean mock();

        @WithDefault("k3s")
        String provider();

        @WithDefault("rancher/k3s:latest")
        String defaultImage();

        @WithDefault("6500")
        int apiServerBasePort();

        @WithDefault("6599")
        int apiServerMaxPort();

        @WithDefault("./data/eks")
        String dataPath();

        /** Docker network to attach k3s containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();

        @WithDefault("false")
        boolean keepRunningOnShutdown();

        /**
         * Controls the endpoint that {@code describe-cluster} returns in real mode:
         * <ul>
         *   <li>{@code host} (default) — {@code https://localhost:<hostPort>}, reachable from the
         *       host so {@code kubectl}/{@code aws eks} work out of the box.</li>
         *   <li>{@code network} — the container DNS name {@code https://floci-eks-<name>:6443},
         *       reachable from other containers on the Docker network (pre-#1118 behaviour). Falls
         *       back to the host endpoint when Floci runs natively.</li>
         * </ul>
         */
        @WithDefault("host")
        String endpointMode();

        /**
         * When true, wires a token-authentication webhook into k3s so that the bearer token
         * produced by {@code aws eks get-token} is validated by Floci and mapped to cluster-admin.
         * This makes the native {@code aws eks update-kubeconfig} + {@code kubectl} flow work.
         */
        @WithDefault("true")
        boolean iamAuthWebhook();

        /**
         * When true (and ECR is enabled), each new k3s cluster gets a generated
         * {@code /etc/rancher/k3s/registries.yaml} that mirrors every ECR repository URI the
         * emulator can mint to the registry container's in-network endpoint, so pods can pull
         * images pushed to Floci ECR without any manual containerd configuration.
         */
        @WithDefault("true")
        boolean ecrRegistryMirror();

        /**
         * When true, starts k3s with {@code --flannel-backend=none --disable-network-policy
         * --disable-kube-proxy} instead of its bundled networking stack. k3s's default flannel CNI
         * and kube-proxy run embedded in the k3s server process itself (not separate, killable
         * DaemonSets), so a real CNI (e.g. Cilium) can only cleanly take over if k3s never starts
         * its own in the first place — there is no way to evict them after the fact. CoreDNS,
         * local-path-provisioner, and metrics-server are unaffected; they don't depend on which CNI
         * is in place.
         */
        @WithDefault("false")
        boolean disableCni();
    }

    /**
     * MWAA (Managed Workflows for Apache Airflow), backed by a real Apache Airflow instance
     * (LocalExecutor) plus a dedicated Postgres metadata database, one pair of containers per
     * environment. See {@code services/mwaa/MwaaEnvironmentManager}.
     */
    interface MwaaServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** When true, environments go straight to AVAILABLE without starting real Docker containers. */
        @WithDefault("false")
        boolean mock();

        /** Image for the per-environment Postgres metadata database. Not shared with RDS's config knob. */
        @WithDefault("postgres:16-alpine")
        String defaultPostgresImage();

        /** Airflow versions environments may request. Combined with the image tag
         *  {@code apache/airflow:<version>-python3.12}. */
        @WithDefault("2.10.5,2.9.3,2.8.4")
        List<String> supportedVersions();

        /** Airflow version used when {@code CreateEnvironment} omits {@code AirflowVersion}. */
        @WithDefault("2.10.5")
        String defaultVersion();

        /** Base port of the web/CLI proxy port range. First environment gets this port. */
        @WithDefault("8700")
        int proxyBasePort();

        /** Inclusive upper bound of the proxy port range. */
        @WithDefault("8799")
        int proxyMaxPort();

        @WithDefault("./data/mwaa")
        String dataPath();

        /** Docker network to attach the Postgres/Airflow containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();

        @WithDefault("false")
        boolean keepRunningOnShutdown();

        /** Poll interval for syncing DAGs (and optionally requirements) from the environment's
         *  S3 {@code DagS3Path} into the Airflow container. */
        @WithDefault("30")
        int dagSyncIntervalSeconds();

        /** When true, {@code RequirementsS3Path} is installed via {@code pip install -r} on create
         *  and on every DAG-sync pass in which the requirements file's ETag changed. */
        @WithDefault("true")
        boolean installRequirements();
    }

    interface InitHooksConfig {
        @WithDefault("/bin/sh")
        String shellExecutable();

        @WithDefault("2")
        long shutdownGracePeriodSeconds();

        @WithDefault("30")
        long timeoutSeconds();
    }

    /**
     * Optional TLS configuration for enabling HTTPS on the Floci server.
     * When enabled, all endpoints are reachable via {@code https://} and
     * WebSocket connections work via {@code wss://}.
     *
     * <p>Both HTTP and HTTPS are served simultaneously (LocalStack parity).
     */
    interface TlsConfig {
        /** Enable TLS/HTTPS on the server. Env: FLOCI_TLS_ENABLED */
        @WithDefault("false")
        boolean enabled();

        /** Path to PEM certificate file. Env: FLOCI_TLS_CERT_PATH */
        Optional<String> certPath();

        /** Path to PEM private key file. Env: FLOCI_TLS_KEY_PATH */
        Optional<String> keyPath();

        /**
         * Auto-generate a self-signed certificate when no cert-path/key-path provided.
         * The generated files are persisted to {@code {storage.persistent-path}/tls/}
         * and reused across restarts. Env: FLOCI_TLS_SELF_SIGNED
         */
        @WithDefault("true")
        boolean selfSigned();

        /**
         * Additional port the TLS proxy binds for AWS-style HTTPS traffic, alongside the
         * public Floci {@link EmulatorConfig#port()}.
         *
         * <p>CDK/CloudFormation custom resources send their {@code cfn-response} callback with
         * bundled code that hardcodes {@code https://} and ignores the port in the ResponseURL,
         * so the PUT lands on the conventional 443 regardless of Floci's configured port. Binding
         * 443 here (with the same HTTP/HTTPS protocol detection used on the main port) lets those
         * callbacks — and any other client that assumes AWS lives on 443 — reach Floci.
         *
         * <p>Default {@code 443}. Set to {@code 0} to disable the extra binding (e.g. when Floci
         * runs unprivileged or another process owns 443). When equal to {@link EmulatorConfig#port()}
         * only a single listener is started. Env: FLOCI_TLS_AWS_HTTPS_PORT
         */
        @WithDefault("443")
        int awsHttpsPort();
    }

    /**
     * Configuration for Docker container management shared across all services
     * that spawn Docker containers (Lambda, RDS, ElastiCache, ECS, ECR, MSK).
     */
    interface DockerConfig {
        /**
         * Maximum size of each container log file before rotation.
         * Uses Docker's json-file log driver max-size option format (e.g., "10m", "100k", "1g").
         */
        @WithDefault("10m")
        String logMaxSize();

        /**
         * Maximum number of rotated log files to retain per container.
         * When this limit is reached, the oldest log file is deleted.
         */
        @WithDefault("3")
        String logMaxFile();

        /** Unix socket or TCP URL for the Docker daemon (e.g. unix:///var/run/docker.sock). */
        @WithDefault("unix:///var/run/docker.sock")
        String dockerHost();

        /**
         * Optional namespace inserted into Floci-managed child container and volume names.
         * Useful when multiple Floci processes share one Docker daemon.
         */
        Optional<String> resourceNamespace();

        /**
         * Optional registry/repository base for every Docker image Floci launches.
         * When set, images such as {@code postgres:16-alpine} and
         * {@code public.ecr.aws/docker/library/ubuntu:24.04} resolve under this
         * base before the container is created.
         */
        Optional<String> imageRegistryBase();

        /**
         * Path to a directory containing Docker's config.json (e.g. /root/.docker).
         * When set, overrides the system default. Useful when Floci runs inside Docker
         * and the host ~/.docker directory is mounted in.
         */
        Optional<String> dockerConfigPath();

        /**
         * Explicit credentials for private Docker registries.
         * Each entry maps a registry hostname to a username/password pair.
         * Use when mounting the host Docker config is impractical.
         */
        @WithDefault("")
        List<RegistryCredential> registryCredentials();

        interface RegistryCredential {
            /** Registry hostname (e.g. myregistry.example.com). */
            String server();
            String username();
            String password();
        }

        /**
         * Extra Docker labels applied to every container and volume Floci creates,
         * alongside the reserved {@code floci}, {@code floci_emulator} and
         * {@code floci_namespace} labels (entries using a reserved key are ignored).
         * A list of key/value entries rather than a {@code Map} so label keys with
         * characters outside SmallRye's env-var naming convention (dots, colons,
         * mixed case) survive:
         * {@code FLOCI_DOCKER_EXTRA_LABELS_0__KEY} / {@code FLOCI_DOCKER_EXTRA_LABELS_0__VALUE}.
         */
        @WithDefault("")
        List<LabelEntry> extraLabels();

        interface LabelEntry {
            String key();
            String value();
        }
    }
}
