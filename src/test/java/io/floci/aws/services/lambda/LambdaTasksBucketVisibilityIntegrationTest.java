package io.floci.aws.services.lambda;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.LambdaCodeFixtures;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaTasksBucketVisibilityIntegrationTest {

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void deploymentBucketsStayOutOfBothSdkListingPaths() {
        try (S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                "test", Region.US_EAST_1, baseUri);
             LambdaClient lambda = AwsTestClients.create(LambdaClient.builder(), "test", Region.US_EAST_1, baseUri)) {
            String suffix = UUID.randomUUID().toString();
            String customerBucket = "awslambda-customer-" + suffix + "-tasks";
            String first = "tasks-first-" + suffix;
            String second = "tasks-second-" + suffix;
            List<String> createdFunctions = new ArrayList<>();
            s3.createBucket(request -> request.bucket(customerBucket));
            try {
                assertEquals(200, s3.createBucket(request -> request.bucket(customerBucket))
                        .sdkHttpResponse().statusCode());
                byte[] zip = LambdaCodeFixtures.nodeJsZip("exports.handler = async () => 'ok';");
                FunctionCode code = FunctionCode.builder().zipFile(SdkBytes.fromByteArray(zip)).build();
                for (String name : List.of(first, second)) {
                    lambda.createFunction(request -> request.functionName(name).runtime("nodejs20.x")
                            .handler("index.handler").role("arn:aws:iam::000000000000:role/lambda-role")
                            .code(code));
                    createdFunctions.add(name);
                    BucketAlreadyExistsException conflict = assertThrows(BucketAlreadyExistsException.class,
                            () -> s3.createBucket(request -> request.bucket(
                                    LambdaService.tasksBucketName("us-east-1"))));
                    assertEquals(409, conflict.statusCode());
                    assertEquals("The requested bucket name is not available. "
                            + "The bucket namespace is shared by all users of the system. "
                            + "Please select a different name and try again.", conflict.awsErrorDetails().errorMessage());
                }
                assertBucketVisibility(s3, customerBucket);
                lambda.deleteFunction(request -> request.functionName(first));
                createdFunctions.remove(first);
                assertEquals(second, lambda.getFunction(request -> request.functionName(second))
                        .configuration().functionName());
                assertBucketVisibility(s3, customerBucket);
                lambda.deleteFunction(request -> request.functionName(second));
                createdFunctions.remove(second);
                assertBucketVisibility(s3, customerBucket);
            } finally {
                for (String name : createdFunctions) {
                    try {
                        lambda.deleteFunction(request -> request.functionName(name));
                    } catch (ResourceNotFoundException ignored) {
                        // A failed delete response may arrive after the function was removed.
                    }
                }
                s3.deleteBucket(request -> request.bucket(customerBucket));
            }
        }
    }

    private static void assertBucketVisibility(S3Client s3, String customerBucket) {
        List<String> unpaginated = s3.listBuckets().buckets().stream().map(Bucket::name).toList();
        List<String> paginated = s3.listBucketsPaginator(request -> request.maxBuckets(1))
                .buckets().stream().map(Bucket::name).toList();
        for (List<String> names : List.of(unpaginated, paginated)) {
            assertTrue(names.contains(customerBucket), "Customer buckets with similar names remain visible");
            assertFalse(names.contains(LambdaService.tasksBucketName("us-east-1")),
                    "Lambda package storage must not appear in customer bucket listings");
        }
    }
}
