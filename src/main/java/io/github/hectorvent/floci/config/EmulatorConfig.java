package io.github.hectorvent.floci.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "floci")
public interface EmulatorConfig {

    @WithDefault("http://localhost:4566")
    String baseUrl();

    @WithDefault("us-east-1")
    String defaultRegion();

    @WithDefault("000000000000")
    String defaultAccountId();

    StorageConfig storage();

    AuthConfig auth();

    ServicesConfig services();

    interface StorageConfig {
        @WithDefault("hybrid")
        String mode();

        @WithDefault("./data")
        String persistentPath();

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
    }

    interface SsmStorageConfig {
        @WithDefault("memory")
        String mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface SqsStorageConfig {
        @WithDefault("memory")
        String mode();
    }

    interface S3StorageConfig {
        @WithDefault("hybrid")
        String mode();
    }

    interface DynamoDbStorageConfig {
        @WithDefault("memory")
        String mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface SnsStorageConfig {
        @WithDefault("memory")
        String mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface LambdaStorageConfig {
        @WithDefault("memory")
        String mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CloudWatchLogsStorageConfig {
        @WithDefault("memory")
        String mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface CloudWatchMetricsStorageConfig {
        @WithDefault("memory")
        String mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface SecretsManagerStorageConfig {
        @WithDefault("memory")
        String mode();

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
        ElastiCacheServiceConfig elasticache();
        RdsServiceConfig rds();
        EventBridgeServiceConfig eventbridge();
        CloudWatchLogsServiceConfig cloudwatchlogs();
        CloudWatchMetricsServiceConfig cloudwatchmetrics();
        SecretsManagerServiceConfig secretsmanager();
        ApiGatewayV2ServiceConfig apigatewayv2();
        KinesisServiceConfig kinesis();
        KmsServiceConfig kms();
        CognitoServiceConfig cognito();
        StepFunctionsServiceConfig stepfunctions();
        CloudFormationServiceConfig cloudformation();
        AcmServiceConfig acm();
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
    }
}