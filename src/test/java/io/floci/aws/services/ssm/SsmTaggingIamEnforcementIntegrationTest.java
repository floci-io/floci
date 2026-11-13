package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.ResourceTypeForTagging;

import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tagging actions are checked against the parameter their {@code ResourceId} names. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmTaggingIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aDenyOnAParameterRefusesTaggingItWhateverNameTheBodyCarries() {
        String prefix = "/iam-tagging-" + UUID.randomUUID();
        String allowed = prefix + "/allowed";
        String denied = prefix + "/denied";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed).type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(denied).type(ParameterType.STRING).value("v"));
                String userName = "ssm-tagging-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":["ssm:AddTagsToResource","ssm:ListTagsForResource",
                            "ssm:RemoveTagsFromResource"],"Resource":"*"},
                          {"Effect":"Deny","Action":["ssm:AddTagsToResource","ssm:ListTagsForResource",
                            "ssm:RemoveTagsFromResource"],
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(denied));

                for (String operation : new String[] {"AddTagsToResource", "ListTagsForResource",
                        "RemoveTagsFromResource"}) {
                    given()
                            .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                            .header("X-Amz-Target", "AmazonSSM." + operation)
                            .contentType("application/x-amz-json-1.1")
                            .body("{\"ResourceType\":\"Parameter\",\"ResourceId\":\"" + denied + "\",\"Name\":\""
                                    + allowed + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"x\"}],"
                                    + "\"TagKeys\":[\"team\"]}")
                    .when().post("/")
                    .then().statusCode(400).body(containsString("AccessDeniedException"));
                }

                given()
                        .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                        .header("X-Amz-Target", "AmazonSSM.AddTagsToResource")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"ResourceType\":\"Parameter\",\"ResourceId\":\"" + allowed + "\",\"Name\":\""
                                + denied + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"x\"}]}")
                .when().post("/")
                .then().statusCode(200);

                assertTrue(root.listTagsForResource(r -> r.resourceType(ResourceTypeForTagging.PARAMETER)
                        .resourceId(denied)).tagList().isEmpty());
            } finally {
                root.deleteParameter(r -> r.name(denied));
                root.deleteParameter(r -> r.name(allowed));
            }
        }
    }

    @Test
    void aDenyOnAParameterRefusesTaggingItByItsArnInAnotherRegion() {
        String denied = "/iam-tagging-" + UUID.randomUUID() + "/denied";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(denied).type(ParameterType.STRING).value("v"));
                String userName = "ssm-tagging-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"ssm:AddTagsToResource","Resource":"*"},
                          {"Effect":"Deny","Action":"ssm:AddTagsToResource",
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(denied));

                given()
                        .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                        .header("X-Amz-Target", "AmazonSSM.AddTagsToResource")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"ResourceType\":\"Parameter\",\"ResourceId\":\"arn:aws:ssm:us-west-2:"
                                + ACCOUNT + ":parameter" + denied
                                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"x\"}]}")
                .when().post("/")
                .then().statusCode(400).body(containsString("AccessDeniedException"));

                assertTrue(root.listTagsForResource(r -> r.resourceType(ResourceTypeForTagging.PARAMETER)
                        .resourceId(denied)).tagList().isEmpty());
            } finally {
                root.deleteParameter(r -> r.name(denied));
            }
        }
    }

    @Test
    void aGrantOnAParameterAllowsTaggingItWhateverNameTheBodyCarries() {
        String prefix = "/iam-tagging-grant-" + UUID.randomUUID();
        String allowed = prefix + "/allowed";
        String other = prefix + "/other";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed).type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(other).type(ParameterType.STRING).value("v"));
                String userName = "ssm-tagging-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:AddTagsToResource",
                          "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(allowed));

                given()
                        .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                        .header("X-Amz-Target", "AmazonSSM.AddTagsToResource")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"ResourceType\":\"Parameter\",\"ResourceId\":\"" + allowed + "\",\"Name\":\""
                                + other + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"x\"}]}")
                .when().post("/")
                .then().statusCode(200);

                assertTrue(root.listTagsForResource(r -> r.resourceType(ResourceTypeForTagging.PARAMETER)
                        .resourceId(other)).tagList().isEmpty());
            } finally {
                root.deleteParameter(r -> r.name(other));
                root.deleteParameter(r -> r.name(allowed));
            }
        }
    }

    @Test
    void aLegacyArnWithoutParameterSlashIsCheckedAgainstTheParameterItTags() {
        String denied = "iam-tagging-legacy-" + UUID.randomUUID();
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(denied).type(ParameterType.STRING).value("v"));
                String userName = "ssm-tagging-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"ssm:AddTagsToResource","Resource":"*"},
                          {"Effect":"Deny","Action":"ssm:AddTagsToResource",
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/%s"}]}""".formatted(denied));

                given()
                        .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                        .header("X-Amz-Target", "AmazonSSM.AddTagsToResource")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"ResourceType\":\"Parameter\",\"ResourceId\":\"arn:aws:ssm:us-east-1:"
                                + ACCOUNT + ":parameter" + denied
                                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"x\"}]}")
                .when().post("/")
                .then().statusCode(400).body(containsString("AccessDeniedException"));

                assertTrue(root.listTagsForResource(r -> r.resourceType(ResourceTypeForTagging.PARAMETER)
                        .resourceId(denied)).tagList().isEmpty());
            } finally {
                root.deleteParameter(r -> r.name(denied));
            }
        }
    }
}
