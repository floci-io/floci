package io.github.hectorvent.floci.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "floci")
public interface EmulatorConfig {

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
     * Returns the effective base URL, taking hostname into account.
     * If hostname is set, replaces the host in baseUrl with it.
     */
    default String effectiveBaseUrl() {
        return hostname()
                .map(h -> baseUrl().replaceFirst("://[^:/]+(:\\d+)?", "://" + h + "$1"))
                .orElse(baseUrl());
    }

    @WithDefault("us-east-1")
    String defaultRegion();

    @WithDefault("us-east-1a")
    String defaultAvailabilityZone();

    @WithDefault("000000000000")
    String defaultAccountId();

    @WithDefault("512")
    int maxRequestSize();

    @WithDefault("public.ecr.aws")
    String ecrBaseUri();

    StorageConfig storage();

    AuthConfig auth();

    ServicesConfig services();

    InitHooksConfig initHooks();

    interface StorageConfig {
        @WithDefault("hybrid")
        String mode();

        @WithDefault("./data")
        String persistentPath();

        /** The path on the host machine where data is stored. Useful for Docker-in-Docker. */
        @WithDefault("${floci.storage.persistent-path}")
        String hostPersistentPath();

        WalConfig wal();

        ServiceStorageOverrides services();
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
        ElastiCacheServiceConfig elasticache();
        RdsServiceConfig rds();
        EventBridgeServiceConfig eventbridge();
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

        @WithDefault("262144")
        int maxMessageSize();
    }

    interface S3ServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("3600")
        int defaultPresignExpirySeconds();
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
    }

    interface MskServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("redpandadata/redpanda:latest")
        String defaultImage();
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

        /** Docker network to attach Valkey containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();
    }

    interface RdsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("7000")
        int proxyBasePort();

        @WithDefault("7099")
        int proxyMaxPort();

        @WithDefault("postgres:16-alpine")
        String defaultPostgresImage();

        @WithDefault("mysql:8.0")
        String defaultMysqlImage();

        @WithDefault("mariadb:11")
        String defaultMariadbImage();

        /** Docker network to attach DB containers to. Empty = default bridge. */
        Optional<String> dockerNetwork();
    }

    interface EventBridgeServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SchedulerServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudWatchLogsServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("10000")
        int maxEventsPerQuery();
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
    }

    interface CloudFormationServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface AcmServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** Seconds to wait before transitioning from PENDING_VALIDATION to ISSUED (0 = immediate) */
        @WithDefault("0")
        int validationWaitSeconds();
    }

    interface AthenaServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface GlueServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SesServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface OpenSearchServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("mock")
        String mode();

        @WithDefault("opensearchproject/opensearch:2")
        String defaultImage();

        @WithDefault("9400")
        int proxyBasePort();

        @WithDefault("9499")
        int proxyMaxPort();

        Optional<String> dockerNetwork();
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

        @WithDefault("unix:///var/run/docker.sock")
        String dockerHost();

        Optional<String> dockerHostOverride();

        @WithDefault("9200")
        int runtimeApiBasePort();

        @WithDefault("9299")
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
    }

    interface Ec2ServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface AppConfigServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface AppConfigDataServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface InitHooksConfig {
        @WithDefault("/bin/sh")
        String shellExecutable();

        @WithDefault("2")
        long shutdownGracePeriodSeconds();

        @WithDefault("30")
        long timeoutSeconds();
    }
}
