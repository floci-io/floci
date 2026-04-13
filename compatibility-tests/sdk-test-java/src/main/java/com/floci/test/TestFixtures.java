package com.floci.test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.opensearch.OpenSearchClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.endpoints.S3ControlEndpointProvider;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;

import java.net.URI;
import java.util.UUID;

/**
 * Shared test utilities and AWS client factories.
 */
public final class TestFixtures {

    private static final URI ENDPOINT;

    static {
        String endpointStr = System.getenv("FLOCI_ENDPOINT");
        if (endpointStr == null || endpointStr.trim().isEmpty()) {
            endpointStr = "http://localhost:4566";
        }
        ENDPOINT = URI.create(endpointStr);
    }

    private static final Region REGION = Region.US_EAST_1;

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    private TestFixtures() {}

    /**
     * Returns true when running against real AWS (no endpoint override).
     */
    public static boolean isRealAws() {
        return "aws".equalsIgnoreCase(System.getenv("FLOCI_TARGET"));
    }

    /**
     * Generate a unique name for test resources.
     */
    public static String uniqueName() {
        return "junit-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Generate a unique name with a prefix.
     */
    public static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Get the Floci endpoint URI.
     */
    public static URI endpoint() {
        return ENDPOINT;
    }

    /**
     * Get the proxy host for direct TCP connections (JDBC, Redis).
     */
    public static String proxyHost() {
        return ENDPOINT.getHost();
    }

    // ============================================
    // AWS Client Factories
    // ============================================

    public static SsmClient ssmClient() {
        return SsmClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static SqsClient sqsClient() {
        return SqsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static SnsClient snsClient() {
        return SnsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .forcePathStyle(true)
                .build();
    }

    /**
     * S3 Control client for the S3 Control API (/v20180820/...).
     * Host prefix injection (account-ID prepended to host) is disabled so requests
     * go to the configured endpoint directly rather than 000000000000.localhost:4566.
     */
    public static S3ControlClient s3ControlClient() {
        URI endpoint = ENDPOINT;
        return S3ControlClient.builder()
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .endpointProvider((S3ControlEndpointProvider) params ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                Endpoint.builder().url(endpoint).build()))
                .build();
    }

    public static DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static LambdaClient lambdaClient() {
        return LambdaClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static IamClient iamClient() {
        return IamClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static StsClient stsClient() {
        return StsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static KmsClient kmsClient() {
        return KmsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static KinesisClient kinesisClient() {
        return KinesisClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static CloudWatchClient cloudWatchClient() {
        return CloudWatchClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static CloudWatchLogsClient cloudWatchLogsClient() {
        return CloudWatchLogsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static CognitoIdentityProviderClient cognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static CloudFormationClient cloudFormationClient() {
        return CloudFormationClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static EventBridgeClient eventBridgeClient() {
        return EventBridgeClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static SfnClient sfnClient() {
        return SfnClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static SesClient sesClient() {
        return SesClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static RdsClient rdsClient() {
        return RdsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static ElastiCacheClient elastiCacheClient() {
        return ElastiCacheClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static ApiGatewayClient apiGatewayClient() {
        return ApiGatewayClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static ApiGatewayV2Client apiGatewayV2Client() {
        return ApiGatewayV2Client.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static OpenSearchClient openSearchClient() {
        return OpenSearchClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static Ec2Client ec2Client() {
        return Ec2Client.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static AcmClient acmClient() {
        return AcmClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static EcrClient ecrClient() {
        return EcrClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static EcsClient ecsClient() {
        return EcsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static SchedulerClient schedulerClient() {
        return SchedulerClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static AppConfigClient appConfigClient() {
        return AppConfigClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static AppConfigDataClient appConfigDataClient() {
        return AppConfigDataClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }
}
