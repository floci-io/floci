package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IAM resources are minted in the partition of the request that creates them, and keep it: a
 * rename signed for another partition does not move a resource.
 */
@QuarkusTest
class IamPartitionArnIntegrationTest {

    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void createdResourcesCarryTheRequestsPartition(PartitionCase partitionCase) {
        String region = partitionCase.region();
        String suffix = partitionCase.partition() + "-" + Long.toString(System.nanoTime(), 36);

        String userName = "partition-user-" + suffix;
        String userArn = iam(region, "CreateUser", "UserName", userName)
                .then().statusCode(200).extract().path("CreateUserResponse.CreateUserResult.User.Arn");
        cleanup.register(() -> iam(region, "DeleteUser", "UserName", userName));
        assertIamArn(partitionCase, "user/" + userName, userArn);

        String groupName = "partition-group-" + suffix;
        String groupArn = iam(region, "CreateGroup", "GroupName", groupName)
                .then().statusCode(200).extract().path("CreateGroupResponse.CreateGroupResult.Group.Arn");
        cleanup.register(() -> iam(region, "DeleteGroup", "GroupName", groupName));
        assertIamArn(partitionCase, "group/" + groupName, groupArn);

        String roleName = "partition-role-" + suffix;
        String roleArn = iam(region, "CreateRole", "RoleName", roleName, "AssumeRolePolicyDocument", TRUST_POLICY)
                .then().statusCode(200).extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> iam(region, "DeleteRole", "RoleName", roleName));
        assertIamArn(partitionCase, "role/" + roleName, roleArn);

        String policyName = "partition-policy-" + suffix;
        String policyArn = iam(region, "CreatePolicy", "PolicyName", policyName, "PolicyDocument", POLICY)
                .then().statusCode(200).extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam(region, "DeletePolicy", "PolicyArn", policyArn));
        assertIamArn(partitionCase, "policy/" + policyName, policyArn);

        String profileName = "partition-profile-" + suffix;
        String profileArn = iam(region, "CreateInstanceProfile", "InstanceProfileName", profileName)
                .then().statusCode(200)
                .extract().path("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
        cleanup.register(() -> iam(region, "DeleteInstanceProfile", "InstanceProfileName", profileName));
        assertIamArn(partitionCase, "instance-profile/" + profileName, profileArn);

        String providerHost = "oidc-" + suffix + ".example.com";
        String providerArn = iam(region, "CreateOpenIDConnectProvider", "Url", "https://" + providerHost,
                "ThumbprintList.member.1", "9e99a48a9960b14926bb7f3b02e22da2b0ab7280")
                .then().statusCode(200)
                .extract().path("CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult"
                        + ".OpenIDConnectProviderArn");
        cleanup.register(() -> iam(region, "DeleteOpenIDConnectProvider", "OpenIDConnectProviderArn", providerArn));
        assertIamArn(partitionCase, "oidc-provider/" + providerHost, providerArn);
    }

    @Test
    void renameSignedForAnotherPartitionKeepsTheUsersPartition() {
        String userName = "partition-rename-" + Long.toString(System.nanoTime(), 36);
        String renamed = userName + "-renamed";
        iam("cn-north-1", "CreateUser", "UserName", userName).then().statusCode(200);
        cleanup.register(() -> iam("cn-north-1", "DeleteUser", "UserName", renamed));

        iam("us-east-1", "UpdateUser", "UserName", userName, "NewUserName", renamed).then().statusCode(200);

        String arn = iam("us-east-1", "GetUser", "UserName", renamed)
                .then().statusCode(200).extract().path("GetUserResponse.GetUserResult.User.Arn");
        assertEquals("arn:aws-cn:iam::" + PartitionMatrix.ACCOUNT + ":user/" + renamed, arn);
    }

    /** A report generated for one partition is never handed to a caller in another. */
    @Test
    void eachPartitionGetsItsOwnCredentialReport() {
        assertEquals("arn:aws-iso-f:iam::" + PartitionMatrix.ACCOUNT + ":root",
                credentialReportRootArn("us-isof-south-1"));
        assertEquals("arn:aws-iso-e:iam::" + PartitionMatrix.ACCOUNT + ":root",
                credentialReportRootArn("eu-isoe-west-1"));
    }

    private static String credentialReportRootArn(String region) {
        iam(region, "GenerateCredentialReport").then().statusCode(200);
        String content = iam(region, "GetCredentialReport").then().statusCode(200)
                .extract().path("GetCredentialReportResponse.GetCredentialReportResult.Content");
        String report = new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        return report.lines().filter(line -> line.startsWith("<root_account>,")).findFirst()
                .orElseThrow().split(",")[1];
    }

    private static void assertIamArn(PartitionCase partitionCase, String resource, String arn) {
        assertEquals("arn:" + partitionCase.partition() + ":iam::" + PartitionMatrix.ACCOUNT + ":" + resource, arn);
    }

    private static Response iam(String region, String action, String... params) {
        RequestSpecification request = given()
                .header("Authorization", PartitionMatrix.sigV4Auth(region, "iam"))
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        for (int i = 0; i < params.length; i += 2) {
            request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/");
    }
}
