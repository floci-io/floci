package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.BufferingHints;
import software.amazon.awssdk.services.firehose.model.CompressionFormat;
import software.amazon.awssdk.services.firehose.model.CreateDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DeleteDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DeliveryStreamStatus;
import software.amazon.awssdk.services.firehose.model.DeliveryStreamType;
import software.amazon.awssdk.services.firehose.model.DescribeDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.ExtendedS3DestinationConfiguration;
import software.amazon.awssdk.services.firehose.model.ProcessingConfiguration;
import software.amazon.awssdk.services.firehose.model.Processor;
import software.amazon.awssdk.services.firehose.model.ProcessorParameter;
import software.amazon.awssdk.services.firehose.model.ProcessorParameterName;
import software.amazon.awssdk.services.firehose.model.ProcessorType;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that a transforming stream really invokes its function: the
 * records put through the SDK come back out of S3 uppercased by a Node.js
 * handler, the one it dropped is nowhere, and the one it failed lands under the
 * evaluated ErrorOutputPrefix as an AWS-shaped NDJSON error line.
 *
 * This is the only layer that runs the function for real. The emulator's own
 * tests mock the Lambda service, so the invocation payload, the base64 round
 * trip through the runtime, and the ARN resolution from the flusher thread are
 * exercised here and nowhere else.
 */
@DisplayName("Firehose record transformation delivery (Lambda)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseLambdaTransformDeliveryTest {

    private static final int BUFFERING_INTERVAL_SECONDS = 5;
    private static final long POLL_TIMEOUT_MILLIS = 240_000L;
    private static final String KEPT_RECORD = "keep-me\n";
    private static final String DROPPED_RECORD = "DROP-me\n";
    private static final String FAILED_RECORD = "FAIL-me\n";

    private static FirehoseClient firehose;
    private static LambdaClient lambda;
    private static S3Client s3;
    private static String bucket;
    private static String streamName;
    private static String functionName;

    @BeforeAll
    static void setup() throws InterruptedException {
        String firehoseRole = TestFixtures.isRealAws()
                ? System.getenv("FIREHOSE_ROLE_ARN")
                : "arn:aws:iam::000000000000:role/firehose-role";
        String lambdaRole = TestFixtures.isRealAws()
                ? System.getenv("LAMBDA_ROLE_ARN")
                : "arn:aws:iam::000000000000:role/lambda-role";
        if (TestFixtures.isRealAws() && (firehoseRole == null || lambdaRole == null)) {
            Assumptions.abort("FIREHOSE_ROLE_ARN and LAMBDA_ROLE_ARN not set");
        }

        firehose = TestFixtures.firehoseClient();
        lambda = TestFixtures.lambdaClient();
        s3 = TestFixtures.s3Client();
        bucket = TestFixtures.uniqueName("firehose-transform");
        streamName = TestFixtures.uniqueName("sdk-transform");
        functionName = TestFixtures.uniqueName("sdk-transform-fn");

        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        String functionArn = lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(functionName)
                .runtime(Runtime.NODEJS20_X)
                .role(lambdaRole)
                .handler("index.handler")
                .timeout(30)
                .memorySize(256)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.firehoseTransformZip()))
                        .build())
                .build()).functionArn();

        firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(streamName)
                .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                .extendedS3DestinationConfiguration(ExtendedS3DestinationConfiguration.builder()
                        .roleARN(firehoseRole)
                        .bucketARN("arn:aws:s3:::" + bucket)
                        .prefix("data/")
                        .errorOutputPrefix("errors/!{firehose:error-output-type}/")
                        .compressionFormat(CompressionFormat.UNCOMPRESSED)
                        .bufferingHints(BufferingHints.builder()
                                .sizeInMBs(5)
                                .intervalInSeconds(BUFFERING_INTERVAL_SECONDS)
                                .build())
                        .processingConfiguration(ProcessingConfiguration.builder()
                                .enabled(true)
                                .processors(Processor.builder()
                                        .type(ProcessorType.LAMBDA)
                                        .parameters(ProcessorParameter.builder()
                                                .parameterName(ProcessorParameterName.LAMBDA_ARN)
                                                .parameterValue(functionArn)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());

        awaitStreamActive();

        assertThat(firehose.putRecordBatch(PutRecordBatchRequest.builder()
                .deliveryStreamName(streamName)
                .records(record(KEPT_RECORD), record(DROPPED_RECORD), record(FAILED_RECORD))
                .build()).failedPutCount()).isZero();
    }

    @Test
    @Order(1)
    @DisplayName("the delivered object holds what the function returned, and nothing it dropped")
    void deliversTheTransformedRecord() throws InterruptedException {
        String delivered = new String(awaitObject("data/"), StandardCharsets.UTF_8);

        assertThat(delivered).contains("KEEP-ME");
        assertThat(delivered).doesNotContain("keep-me");
        assertThat(delivered).doesNotContain("DROP-me").doesNotContain("DROP-ME");
        assertThat(delivered).doesNotContain("FAIL-me").doesNotContain("FAIL-ME");
    }

    @Test
    @Order(2)
    @DisplayName("a ProcessingFailed record lands under the processing-failed error output")
    void writesTheFailedRecordToTheErrorOutput() throws InterruptedException {
        String errorLine = new String(awaitObject("errors/processing-failed/"), StandardCharsets.UTF_8).strip();

        assertThat(errorLine).contains("\"errorCode\":\"Lambda.ProcessingFailedStatus\"");
        assertThat(errorLine).contains("\"lambdaARN\":\"");
        assertThat(errorLine).contains("\"rawData\":\""
                + Base64.getEncoder().encodeToString(FAILED_RECORD.getBytes(StandardCharsets.UTF_8)) + "\"");
    }

    /**
     * The buffer has to flush and the function has to run, which on a cold container is
     * well past the buffering interval, so both tests poll rather than sleep once.
     */
    private static byte[] awaitObject(String prefix) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        while (true) {
            Optional<S3Object> found = firstObject(prefix);
            if (found.isPresent()) {
                return s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket).key(found.get().key()).build()).asByteArray();
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("no object under " + prefix + " in " + bucket + " after "
                        + POLL_TIMEOUT_MILLIS / 1000 + "s");
            }
            Thread.sleep(5_000L);
        }
    }

    private static Optional<S3Object> firstObject(String prefix) {
        List<S3Object> objects = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefix).build()).contents();
        return objects.isEmpty() ? Optional.empty() : Optional.of(objects.get(0));
    }

    // Real AWS returns from CreateDeliveryStream while the stream is still CREATING
    // and rejects PutRecordBatch until it turns ACTIVE; Floci creates it active, so
    // this returns on the first poll locally.
    private static void awaitStreamActive() throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        while (true) {
            DeliveryStreamStatus status = firehose.describeDeliveryStream(
                            DescribeDeliveryStreamRequest.builder()
                                    .deliveryStreamName(streamName).build())
                    .deliveryStreamDescription().deliveryStreamStatus();
            if (status == DeliveryStreamStatus.ACTIVE) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("delivery stream stayed " + status + " for "
                        + POLL_TIMEOUT_MILLIS / 1000 + "s");
            }
            Thread.sleep(5_000L);
        }
    }

    private static Record record(String data) {
        return Record.builder().data(SdkBytes.fromString(data, StandardCharsets.UTF_8)).build();
    }

    @AfterAll
    static void cleanup() {
        if (firehose != null) {
            try {
                firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder()
                        .deliveryStreamName(streamName).build());
            } catch (Exception e) {
                // Best effort: a stream that never got created must not fail the run.
                System.out.println("Cleanup of delivery stream " + streamName + " failed: " + e);
            }
            firehose.close();
        }
        if (lambda != null) {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(functionName).build());
            } catch (Exception e) {
                // Best effort cleanup of the transform function.
                System.out.println("Cleanup of function " + functionName + " failed: " + e);
            }
            lambda.close();
        }
        if (s3 != null) {
            try {
                s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents()
                        .forEach(object -> s3.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(object.key()).build()));
                s3.deleteBucket(builder -> builder.bucket(bucket));
            } catch (Exception e) {
                // Best effort cleanup of the probe bucket.
                System.out.println("Cleanup of bucket " + bucket + " failed: " + e);
            }
            s3.close();
        }
    }
}
