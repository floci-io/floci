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
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.pipes.PipesClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import software.amazon.awssdk.core.exception.SdkClientException;

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
    // Lambda dispatch availability probe
    // ============================================

    private static volatile Boolean lambdaDispatchAvailable;

    /**
     * Checks whether Lambda REQUEST_RESPONSE invocation works in the current
     * environment. Creates a minimal no-op function, invokes it, and tears it
     * down. The result is memoized so it runs at most once per JVM.
     *
     * Thread-safe: uses double-checked locking so parallel test classes don't
     * race the probe.
     *
     * Returns false on transport-level failures (timeout, connection refused,
     * SDK client timeout) so tests skip cleanly when Docker-in-Docker is
     * unavailable in CI. Unexpected service errors propagate as test failures.
     */
    public static boolean isLambdaDispatchAvailable() {
        if (lambdaDispatchAvailable != null) {
            return lambdaDispatchAvailable;
        }
        synchronized (TestFixtures.class) {
            if (lambdaDispatchAvailable != null) {
                return lambdaDispatchAvailable;
            }
            String probeFn = uniqueName("probe-lambda-dispatch");
            LambdaClient probe = lambdaClient();
            try {
                probe.createFunction(CreateFunctionRequest.builder()
                        .functionName(probeFn)
                        .runtime(Runtime.NODEJS20_X)
                        .role("arn:aws:iam::000000000000:role/lambda-role")
                        .handler("index.handler")
                        .code(FunctionCode.builder()
                                .zipFile(SdkBytes.fromByteArray(probeZip()))
                                .build())
                        .build());
                InvokeResponse response = probe.invoke(InvokeRequest.builder()
                        .functionName(probeFn)
                        .invocationType(InvocationType.REQUEST_RESPONSE)
                        .payload(SdkBytes.fromUtf8String("{}"))
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)))
                        .build());
                lambdaDispatchAvailable = response.statusCode() == 200;
            } catch (SdkClientException e) {
                // SDK-level timeout or connection failure (wraps ConnectException,
                // ApiCallTimeoutException, etc.)
                lambdaDispatchAvailable = false;
            } finally {
                try {
                    probe.deleteFunction(DeleteFunctionRequest.builder()
                            .functionName(probeFn).build());
                } catch (Exception ignored) {
                }
                probe.close();
            }
            return lambdaDispatchAvailable;
        }
    }

    private static byte[] probeZip() {
        String code = "exports.handler = async () => ({ statusCode: 200 });";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write(code.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build probe ZIP", e);
        }
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

    public static KafkaClient kafkaClient() {
        return KafkaClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static AthenaClient athenaClient() {
        return AthenaClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static GlueClient glueClient() {
        return GlueClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }

    public static FirehoseClient firehoseClient() {
        return FirehoseClient.builder()
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

    public static EksClient eksClient() {
        return EksClient.builder()
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

    public static PipesClient pipesClient() {
        return PipesClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build();
    }
}
