package io.floci.aws.services.lambda;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.PublishLayerVersionResponse;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaLayerNameArnIamIntegrationTest {

    private static final String ACCOUNT = "741681410004";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,false", "us-east-1,true", "cn-north-1,false", "cn-north-1,true",
            "us-gov-west-1,false", "us-gov-west-1,true"})
    void nameAndArnLookupsHonorScopedAllowAndExplicitDeny(String region, boolean arnSelector) throws Exception {
        verifyLayerPermissions(Region.of(region), Region.of(region), arnSelector);
    }

    @Test
    void crossRegionArnLookupChecksTheStoredLayerVersion() throws Exception {
        verifyLayerPermissions(Region.US_WEST_2, Region.US_EAST_1, true);
    }

    private void verifyLayerPermissions(Region layerRegion, Region requestRegion, boolean arnSelector) throws Exception {
        String name = "layer-iam-" + UUID.randomUUID();
        byte[] archive = layerArchive();
        PublishLayerVersionResponse published;
        try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, layerRegion, endpoint)) {
            published = owner.publishLayerVersion(request -> request.layerName(name)
                    .content(content -> content.zipFile(SdkBytes.fromByteArray(archive))));
        }
        cleanup.register(() -> {
            try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, layerRegion, endpoint)) {
                owner.deleteLayerVersion(request -> request.layerName(name).versionNumber(published.version()));
            }
        });

        String user = "layer-reader-" + UUID.randomUUID();
        String authorization = auth(ACCOUNT, requestRegion.id(), "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
        String selector = arnSelector ? published.layerArn() : name;
        String resource = published.layerVersionArn();
        try (LambdaClient reader = AwsTestClients.create(LambdaClient.builder(),
                credentials.accessKeyId(), credentials.secretAccessKey(), requestRegion, endpoint)) {
            policy(authorization, user, """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Action": "lambda:GetLayerVersion",
                          "Resource": "*"
                        },
                        {
                          "Effect": "Deny",
                          "Action": "lambda:GetLayerVersion",
                          "Resource": "%s"
                        }
                      ]
                    }
                    """.formatted(resource));
            denied(reader, selector, published.version());

            policy(authorization, user, allow(resource));
            assertEquals(resource, reader.getLayerVersion(request -> request.layerName(selector)
                    .versionNumber(published.version())).layerVersionArn());

            policy(authorization, user, allow(resource + "-other"));
            denied(reader, selector, published.version());
        }
    }

    private void policy(String authorization, String user, String document) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "layer-access", document);
    }

    private static String allow(String resource) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "lambda:GetLayerVersion",
                      "Resource": "%s"
                    }
                  ]
                }
                """.formatted(resource);
    }

    private static void denied(LambdaClient client, String selector, long version) {
        LambdaException denied = assertThrows(LambdaException.class, () -> client.getLayerVersion(request -> request
                .layerName(selector).versionNumber(version)));
        assertEquals(403, denied.statusCode());
        assertTrue(denied.awsErrorDetails().errorCode().contains("AccessDenied"));
    }

    private static byte[] layerArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("python/shared.py"));
            zip.write("VALUE = 1".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
