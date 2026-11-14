package io.floci.aws.services.lambda;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetLayerVersionResponse;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.PublishLayerVersionResponse;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static io.floci.aws.services.lambda.LambdaLayerFixtures.layerArchive;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaLayerNameArnIntegrationTest {

    private static final String ACCOUNT = "741681410002";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void nameAndUnversionedArnReturnTheSameArchive(String region) throws Exception {
        byte[] archive = layerArchive();
        String name = "layer-name-arn-" + UUID.randomUUID();
        try (LambdaClient client = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.of(region))) {
            PublishLayerVersionResponse published = client.publishLayerVersion(request -> request
                    .layerName(name).content(content -> content.zipFile(SdkBytes.fromByteArray(archive))));
            try {
                GetLayerVersionResponse byName = client.getLayerVersion(request -> request
                        .layerName(name).versionNumber(published.version()));
                GetLayerVersionResponse byArn = client.getLayerVersion(request -> request
                        .layerName(published.layerArn()).versionNumber(published.version()));

                assertEquals(published.layerVersionArn(), byArn.layerVersionArn());
                assertEquals(byName.content().codeSha256(), byArn.content().codeSha256());
                assertEquals(Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(archive)), byArn.content().codeSha256());
                assertArrayEquals(archive, given().urlEncodingEnabled(false).get(byArn.content().location())
                        .then().statusCode(200).extract().asByteArray());
            } finally {
                client.deleteLayerVersion(request -> request.layerName(name).versionNumber(published.version()));
            }
        }
    }

    @Test
    void arnLookupKeepsTheStoredArchiveRegion() throws Exception {
        byte[] archive = layerArchive();
        String name = "layer-region-arn-" + UUID.randomUUID();
        try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_WEST_2);
             LambdaClient reader = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            PublishLayerVersionResponse published = owner.publishLayerVersion(request -> request
                    .layerName(name).content(content -> content.zipFile(SdkBytes.fromByteArray(archive))));
            try {
                GetLayerVersionResponse result = reader.getLayerVersion(request -> request
                        .layerName(published.layerArn()).versionNumber(published.version()));

                assertEquals(published.layerVersionArn(), result.layerVersionArn());
                assertTrue(result.content().location().contains(LambdaService.tasksBucketName("us-west-2")));
                assertArrayEquals(archive, given().urlEncodingEnabled(false).get(result.content().location())
                        .then().statusCode(200).extract().asByteArray());
            } finally {
                owner.deleteLayerVersion(request -> request.layerName(name).versionNumber(published.version()));
            }
        }
    }

    @Test
    void invalidArnSelectorsCannotSubstituteTheCallersLayer() throws Exception {
        String name = "layer-invalid-arn-" + UUID.randomUUID();
        byte[] archive = layerArchive();
        try (LambdaClient client = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            PublishLayerVersionResponse published = client.publishLayerVersion(request -> request
                    .layerName(name).content(content -> content.zipFile(SdkBytes.fromByteArray(archive))));
            try {
                String foreignAccount = published.layerArn().replace(ACCOUNT, "741681410003");
                assertThrows(ResourceNotFoundException.class, () -> client.getLayerVersion(request -> request
                        .layerName(foreignAccount).versionNumber(published.version())));
                assertThrows(ResourceNotFoundException.class, () -> client.getLayerVersion(request -> request
                        .layerName(published.layerArn()).versionNumber(999999L)));
                LambdaException versioned = assertThrows(LambdaException.class,
                        () -> client.getLayerVersion(request -> request
                                .layerName(published.layerVersionArn()).versionNumber(published.version())));
                assertEquals(400, versioned.statusCode());
                String foreignPartition = published.layerArn().replace("arn:aws:", "arn:aws-cn:");
                LambdaException partition = assertThrows(LambdaException.class,
                        () -> client.getLayerVersion(request -> request
                                .layerName(foreignPartition).versionNumber(published.version())));
                assertEquals(400, partition.statusCode());
                assertEquals(published.layerVersionArn(), client.getLayerVersion(request -> request
                        .layerName(published.layerArn()).versionNumber(published.version())).layerVersionArn());
            } finally {
                client.deleteLayerVersion(request -> request.layerName(name).versionNumber(published.version()));
            }
        }
    }
}
