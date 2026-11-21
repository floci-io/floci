package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.GetOriginAccessControlConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.OriginAccessControlConfig;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.services.cloudfront.CloudFrontOriginAccessControlFixtures.configuration;
import static io.floci.aws.services.cloudfront.CloudFrontOriginAccessControlFixtures.deleteIfPresent;
import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFrontOriginAccessControlIamIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void nativeSdkGrantUpdatesOnlyTheControlNamedByItsResourceArn() {
        String user = "oac-scoped-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        try (CloudFrontClient admin = client(ACCOUNT, "test");
             CloudFrontClient restricted = client(credentials.accessKeyId(), credentials.secretAccessKey())) {
            String allowed = create(admin);
            String other = create(admin);
            String allowedArn = "arn:aws:cloudfront::" + ACCOUNT + ":origin-access-control/" + allowed;
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "update", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Action": "cloudfront:UpdateOriginAccessControl",
                          "Resource": "%s"
                        }
                      ]
                    }
                    """.formatted(allowedArn));

            GetOriginAccessControlConfigResponse before =
                    admin.getOriginAccessControlConfig(request -> request.id(allowed));
            OriginAccessControlConfig changed = before.originAccessControlConfig().toBuilder()
                    .description("scoped SDK update").build();
            restricted.updateOriginAccessControl(request -> request.id(allowed)
                    .ifMatch(before.eTag()).originAccessControlConfig(changed));
            assertEquals(changed, admin.getOriginAccessControlConfig(request -> request.id(allowed))
                    .originAccessControlConfig());

            GetOriginAccessControlConfigResponse untouched =
                    admin.getOriginAccessControlConfig(request -> request.id(other));
            assertAwsError("AccessDenied", 403,
                    () -> restricted.updateOriginAccessControl(request -> request.id(other)
                            .ifMatch(untouched.eTag()).originAccessControlConfig(
                                    untouched.originAccessControlConfig().toBuilder()
                                            .description("must be denied").build())));
            GetOriginAccessControlConfigResponse after =
                    admin.getOriginAccessControlConfig(request -> request.id(other));
            assertEquals(untouched.originAccessControlConfig(), after.originAccessControlConfig());
            assertEquals(untouched.eTag(), after.eTag());
        }
    }

    @Test
    void encodedIdAndMisleadingActionHintsCannotChangeTheMatchedUpdatePermission() {
        String user = "oac-hints-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "read", """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "cloudfront:GetOriginAccessControl",
                      "Resource": "*"
                    }
                  ]
                }
                """);
        try (CloudFrontClient admin = client(ACCOUNT, "test")) {
            String id = create(admin);
            GetOriginAccessControlConfigResponse before =
                    admin.getOriginAccessControlConfig(request -> request.id(id));
            String encodedId = "%%%02X".formatted((int) id.charAt(0)) + id.substring(1);
            given().urlEncodingEnabled(false).port(endpoint.getPort())
                    .header("Authorization", auth(credentials.accessKeyId(), "cloudfront"))
                    .header("X-Amz-Target", "CloudFront.GetOriginAccessControl")
                    .header("If-Match", before.eTag())
                    .contentType("application/xml").body("""
                            <OriginAccessControlConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                              <Name>%s</Name><Description>must be denied</Description>
                              <SigningProtocol>sigv4</SigningProtocol><SigningBehavior>always</SigningBehavior>
                              <OriginAccessControlOriginType>s3</OriginAccessControlOriginType>
                            </OriginAccessControlConfig>
                            """.formatted(before.originAccessControlConfig().name()))
                    .when().put("/2020-05-31/origin-access-control/" + encodedId
                            + "/config?Action=GetOriginAccessControl")
                    .then().statusCode(403).body(containsString("cloudfront:UpdateOriginAccessControl"));
            GetOriginAccessControlConfigResponse after =
                    admin.getOriginAccessControlConfig(request -> request.id(id));
            assertEquals(before.originAccessControlConfig(), after.originAccessControlConfig());
            assertEquals(before.eTag(), after.eTag());
        }
    }

    private CloudFrontClient client(String accessKey, String secretKey) {
        return AwsTestClients.create(CloudFrontClient.builder(), accessKey, secretKey, Region.US_EAST_1, endpoint);
    }

    private String create(CloudFrontClient client) {
        String id = client.createOriginAccessControl(request -> request.originAccessControlConfig(configuration()))
                .originAccessControl().id();
        cleanup.register(() -> {
            try (CloudFrontClient admin = client(ACCOUNT, "test")) {
                deleteIfPresent(admin, id);
            }
        });
        return id;
    }
}
