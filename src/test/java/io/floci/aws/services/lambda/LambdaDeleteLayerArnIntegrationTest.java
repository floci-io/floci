package io.floci.aws.services.lambda;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.PublishLayerVersionResponse;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.services.lambda.LambdaLayerFixtures.layerArchive;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class LambdaDeleteLayerArnIntegrationTest {

    private static final String ACCOUNT = "741681410021";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,false", "us-east-1,true", "cn-north-1,false", "cn-north-1,true",
            "us-gov-west-1,false", "us-gov-west-1,true"})
    void deletesOnlyTheSelectedVersionAndItsArchive(String region, boolean arnSelector) throws Exception {
        try (LambdaClient client = client(ACCOUNT, Region.of(region))) {
            String name = "layer-delete-" + UUID.randomUUID();
            PublishLayerVersionResponse first = publish(client, name, ACCOUNT, Region.of(region));
            PublishLayerVersionResponse second = publish(client, name, ACCOUNT, Region.of(region));
            String location = client.getLayerVersion(request -> request.layerName(name)
                    .versionNumber(first.version())).content().location();
            String selector = arnSelector ? first.layerArn() : name;

            assertEquals(204, client.deleteLayerVersion(request -> request.layerName(selector)
                    .versionNumber(first.version())).sdkHttpResponse().statusCode());
            assertThrows(ResourceNotFoundException.class, () -> client.getLayerVersion(request -> request
                    .layerName(name).versionNumber(first.version())));
            given().urlEncodingEnabled(false).get(location).then().statusCode(404);
            assertEquals(second.layerVersionArn(), client.getLayerVersion(request -> request.layerName(name)
                    .versionNumber(second.version())).layerVersionArn());
            assertEquals(204, client.deleteLayerVersion(request -> request.layerName(selector)
                    .versionNumber(first.version())).sdkHttpResponse().statusCode());
        }
    }

    @Test
    void arnDeletionUsesTheLayerRegionAndPreservesTheRequestRegion() throws Exception {
        try (LambdaClient owner = client(ACCOUNT, Region.US_WEST_2);
             LambdaClient caller = client(ACCOUNT, Region.US_EAST_1)) {
            String name = "layer-regional-delete-" + UUID.randomUUID();
            PublishLayerVersionResponse stored = publish(owner, name, ACCOUNT, Region.US_WEST_2);
            PublishLayerVersionResponse local = publish(caller, name, ACCOUNT, Region.US_EAST_1);
            String location = owner.getLayerVersion(request -> request.layerName(name)
                    .versionNumber(stored.version())).content().location();

            caller.deleteLayerVersion(request -> request.layerName(stored.layerArn()).versionNumber(stored.version()));

            assertThrows(ResourceNotFoundException.class, () -> owner.getLayerVersion(request -> request
                    .layerName(name).versionNumber(stored.version())));
            given().urlEncodingEnabled(false).get(location).then().statusCode(404);
            assertEquals(local.layerVersionArn(), caller.getLayerVersion(request -> request
                    .layerName(name).versionNumber(local.version())).layerVersionArn());
        }
    }

    @Test
    void foreignOrMalformedSelectorsCannotDeleteOwnedVersions() throws Exception {
        String otherAccount = "741681410022";
        try (LambdaClient client = client(ACCOUNT, Region.US_EAST_1);
             LambdaClient other = client(otherAccount, Region.US_EAST_1)) {
            String name = "layer-isolated-delete-" + UUID.randomUUID();
            PublishLayerVersionResponse owned = publish(client, name, ACCOUNT, Region.US_EAST_1);
            PublishLayerVersionResponse foreign = publish(other, name, otherAccount, Region.US_EAST_1);
            client.deleteLayerVersion(request -> request.layerName(foreign.layerArn()).versionNumber(foreign.version()));
            for (String invalid : new String[] {owned.layerVersionArn(),
                    owned.layerArn().replace("arn:aws:", "arn:aws-cn:")}) {
                LambdaException error = assertThrows(LambdaException.class, () -> client.deleteLayerVersion(request ->
                        request.layerName(invalid).versionNumber(owned.version())));
                assertEquals(400, error.statusCode());
            }
            assertEquals(owned.layerVersionArn(), client.getLayerVersion(request -> request
                    .layerName(name).versionNumber(owned.version())).layerVersionArn());
            assertEquals(foreign.layerVersionArn(), other.getLayerVersion(request -> request
                    .layerName(name).versionNumber(foreign.version())).layerVersionArn());
        }
    }

    private LambdaClient client(String account, Region region) {
        return AwsTestClients.create(LambdaClient.builder(), account, region, endpoint);
    }

    private PublishLayerVersionResponse publish(LambdaClient client, String name, String account, Region region)
            throws Exception {
        byte[] archive = layerArchive();
        PublishLayerVersionResponse published = client.publishLayerVersion(request -> request.layerName(name)
                .content(content -> content.zipFile(SdkBytes.fromByteArray(archive))));
        cleanup.register(() -> {
            try (LambdaClient owner = client(account, region)) {
                owner.deleteLayerVersion(request -> request.layerName(name).versionNumber(published.version()));
            }
        });
        return published;
    }
}
