package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterTier;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The resource policy actions are checked against the parameter their {@code ResourceArn} names. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmResourcePolicyIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"AWS":"arn:aws:iam::111122223333:root"},"Action":"ssm:GetParameter","Resource":"*"}]}""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aDenyOnAParameterPathRefusesThePolicyActionsOnIt() {
        String prefix = "/iam-resource-policy-" + UUID.randomUUID();
        String denied = prefix + "/prod/db";
        String allowed = prefix + "/dev/db";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                String deniedArn = advancedParameter(root, denied);
                String allowedArn = advancedParameter(root, allowed);
                String userName = "ssm-policy-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"ssm:*","Resource":"*"},
                          {"Effect":"Deny","Action":["ssm:PutResourcePolicy","ssm:GetResourcePolicies",
                            "ssm:DeleteResourcePolicy","ssm:GetParameter"],
                           "Resource":"arn:aws:ssm:*:*:parameter%s/prod/*"}]}"""
                        .formatted(prefix));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertDoesNotThrow(() -> user.putResourcePolicy(r -> r.resourceArn(allowedArn).policy(POLICY)));
                    assertDoesNotThrow(() -> user.getResourcePolicies(r -> r.resourceArn(allowedArn)));

                    SsmException put = assertThrows(SsmException.class,
                            () -> user.putResourcePolicy(r -> r.resourceArn(deniedArn).policy(POLICY)));
                    assertEquals("AccessDeniedException", put.awsErrorDetails().errorCode());
                    SsmException get = assertThrows(SsmException.class,
                            () -> user.getResourcePolicies(r -> r.resourceArn(deniedArn)));
                    assertEquals("AccessDeniedException", get.awsErrorDetails().errorCode());
                }

                // Another action still acts on the parameter its handler reads, whatever ResourceArn it carries.
                given()
                        .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                        .header("X-Amz-Target", "AmazonSSM.GetParameter")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"Name\":\"" + denied + "\",\"ResourceArn\":\"" + allowedArn + "\"}")
                .when().post("/")
                .then().statusCode(400).body(containsString("AccessDeniedException"));
            } finally {
                root.deleteParameter(r -> r.name(denied));
                root.deleteParameter(r -> r.name(allowed));
            }
        }
    }

    private static String advancedParameter(SsmClient root, String name) {
        root.putParameter(r -> r.name(name).type(ParameterType.STRING).value("v").tier(ParameterTier.ADVANCED));
        return root.getParameter(r -> r.name(name)).parameter().arn();
    }
}
