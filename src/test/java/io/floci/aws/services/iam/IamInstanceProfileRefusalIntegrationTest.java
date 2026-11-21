package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * What the instance profile pair refuses, in the sentences AWS was recorded answering: a second
 * role names the quota that fired, and removing a role the profile never held is a 404 rather than
 * a quiet success.
 *
 * <p>{@code docs/services/iam.md} carries the recordings.
 */
@QuarkusTest
class IamInstanceProfileRefusalIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private String createRole() {
        String roleName = unique("profile-role");
        iam("CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(200));
        return roleName;
    }

    private String createProfile() {
        String profileName = unique("profile");
        iam("CreateInstanceProfile").formParam("InstanceProfileName", profileName)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteInstanceProfile")
                .formParam("InstanceProfileName", profileName)
                .when().post("/").then().statusCode(200));
        return profileName;
    }

    private void addRole(String profileName, String roleName) {
        iam("AddRoleToInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("RemoveRoleFromInstanceProfile")
                .formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200));
    }

    /** The second role names the quota that fired, in the form the other nine quotas use. */
    @Test
    void aSecondRoleNamesTheQuotaThatFired() {
        String profileName = createProfile();
        addRole(profileName, createRole());
        iam("AddRoleToInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", createRole())
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("LimitExceeded"))
                .body(MESSAGE, equalTo(
                        "Cannot exceed quota for InstanceSessionsPerInstanceProfile: 1"));
    }

    /** Removing a role the profile never held is refused rather than answered 200. */
    @Test
    void removingARoleTheProfileNeverHeldIsRefused() {
        String profileName = createProfile();
        String roleName = createRole();
        iam("RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName)
                .when().post("/").then().statusCode(404)
                .body(CODE, equalTo("NoSuchEntity"))
                .body(MESSAGE, equalTo("Role " + roleName + " in Instance Profile "
                        + profileName + " cannot be found."));
    }

    /**
     * A role deleted while the profile still lists it stays removable, which is the case the
     * tolerance was there for and is not the one AWS refuses. The role goes first and the pairing
     * after it, so the removal is the one that reaches a profile listing a role that is not there.
     */
    @Test
    void aRoleDeletedWhileStillListedStaysRemovable() {
        String profileName = createProfile();
        String roleName = unique("profile-ghost-role");
        iam("CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(anyOf(is(200), is(404))));
        iam("AddRoleToInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("RemoveRoleFromInstanceProfile")
                .formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName)
                .when().post("/").then().statusCode(anyOf(is(200), is(404))));
        iam("DeleteRole").formParam("RoleName", roleName).when().post("/").then().statusCode(200);
        iam("RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200);
    }

    /** The control: the first role still attaches, and removing it still succeeds. */
    @Test
    void theFirstRoleStillAttachesAndDetaches() {
        String profileName = createProfile();
        String roleName = createRole();
        iam("AddRoleToInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200);
        iam("RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200);
    }
}
