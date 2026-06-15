package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for IAM and STS via the Query Protocol (form-encoded POST, XML response).
 * Covers the full HTTP stack through {@link AwsQueryController}
 * → {@link IamQueryHandler} and {@link StsQueryHandler}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamIntegrationTest {

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static final String EXPLICIT_DENY_POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
            + "\"Action\":\"ec2:RunInstances\",\"Resource\":\"*\"}]}";

    private static String createdPolicyArn;

    // =========================================================================
    // STS
    // =========================================================================

    @Test
    @Order(1)
    void stsGetCallerIdentity() {
        given()
            .formParam("Action", "GetCallerIdentity")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/sts/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo("000000000000"))
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                    containsString("arn:aws:iam::000000000000:root"));
    }

    @Test
    @Order(2)
    void stsGetCallerIdentityHonoursTwelveDigitAccessKey() {
        given()
            .formParam("Action", "GetCallerIdentity")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=123456789012/20260227/us-east-1/sts/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.UserId", equalTo("123456789012"))
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo("123456789012"))
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                    equalTo("arn:aws:iam::123456789012:root"));
    }

    @Test
    @Order(3)
    void stsAssumeRole() {
        given()
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::000000000000:role/TestRole")
            .formParam("RoleSessionName", "test-session")
            .formParam("DurationSeconds", "3600")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/sts/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId",
                    startsWith("ASIA"))
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.SecretAccessKey", notNullValue())
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken", notNullValue())
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.Expiration", notNullValue())
            .body("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn",
                    containsString("assumed-role/TestRole/test-session"));
    }

    @Test
    @Order(4)
    void stsAssumeRoleHonoursTwelveDigitAccessKey() {
        given()
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::123456789012:role/TestRole")
            .formParam("RoleSessionName", "tenant-session")
            .formParam("DurationSeconds", "3600")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=123456789012/20260227/us-east-1/sts/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn",
                    equalTo("arn:aws:sts::123456789012:assumed-role/TestRole/tenant-session"));
    }

    @Test
    @Order(6)
    void stsAssumeRoleUsesAccountFromRoleArnForCrossAccount() {
        given()
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::222222222222:role/CrossAccountRole")
            .formParam("RoleSessionName", "cross-session")
            .formParam("DurationSeconds", "3600")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=123456789012/20260227/us-east-1/sts/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn",
                    equalTo("arn:aws:sts::222222222222:assumed-role/CrossAccountRole/cross-session"));
    }

    // =========================================================================
    // AWS Managed Policies (seeded at startup)
    // =========================================================================

    @Test
    @Order(5)
    void getManagedPolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AWSLambdaBasicExecutionRole"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn",
                    equalTo("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"));
    }

    // The standard EKS managed policies the EKS console/SDK and the
    // terraform-aws-modules/eks module attach to cluster and node roles (#1092).
    @ParameterizedTest
    @Order(15)
    @ValueSource(strings = {
            "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy",
            "arn:aws:iam::aws:policy/AmazonEKSServicePolicy",
            "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController",
            "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
            "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    })
    void getEksManagedPolicy(String arn) {
        String expectedName = arn.substring(arn.lastIndexOf('/') + 1);
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", arn)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName", equalTo(expectedName))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(arn));
    }

    @Test
    @Order(16)
    void getSsmManagedInstanceCorePolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AmazonSSMManagedInstanceCore"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn",
                    equalTo("arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"));
    }

    @Test
    @Order(7)
    void getCloudWatchAgentServerPolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("CloudWatchAgentServerPolicy"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn",
                    equalTo("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"));
    }

    @Test
    @Order(8)
    void getEcrReadOnlyPolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AmazonEC2ContainerRegistryReadOnly"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn",
                    equalTo("arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"));
    }

    @Test
    @Order(9)
    void stsGetCallerIdentityHonoursSeededFlociAccessKey() {
        given()
            .formParam("Action", "GetCallerIdentity")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=floci/20260227/us-east-1/sts/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo("000000000000"))
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                    equalTo("arn:aws:iam::000000000000:user/floci-deployer"));
    }

    @Test
    @Order(34)
    void simulatePrincipalPolicyEvaluatesIdentityPolicies() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", "simulate-user")
            .formParam("Path", "/")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String policyArn = given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", "SimulatePolicy")
            .formParam("Path", "/")
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        String denyPolicyArn = given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", "SimulateExplicitDenyPolicy")
            .formParam("Path", "/")
            .formParam("PolicyDocument", EXPLICIT_DENY_POLICY_DOCUMENT)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        given()
            .formParam("Action", "AttachUserPolicy")
            .formParam("UserName", "simulate-user")
            .formParam("PolicyArn", policyArn)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachUserPolicy")
            .formParam("UserName", "simulate-user")
            .formParam("PolicyArn", denyPolicyArn)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "SimulatePrincipalPolicy")
            .formParam("PolicySourceArn", "arn:aws:iam::000000000000:user/simulate-user")
            .formParam("ActionNames.member.1", "s3:GetObject")
            .formParam("ActionNames.member.2", "ec2:RunInstances")
            .formParam("ActionNames.member.3", "ssm:GetParameter")
            .formParam("ResourceArns.member.1", "*")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("SimulatePrincipalPolicyResponse.SimulatePrincipalPolicyResult.EvaluationResults.member.find { it.EvalActionName == 's3:GetObject' }.EvalDecision",
                    equalTo("allowed"))
            .body("SimulatePrincipalPolicyResponse.SimulatePrincipalPolicyResult.EvaluationResults.member.find { it.EvalActionName == 'ec2:RunInstances' }.EvalDecision",
                    equalTo("explicitDeny"))
            .body("SimulatePrincipalPolicyResponse.SimulatePrincipalPolicyResult.EvaluationResults.member.find { it.EvalActionName == 'ssm:GetParameter' }.EvalDecision",
                    equalTo("implicitDeny"));
    }

    @Test
    @Order(35)
    void attachManagedPolicyToRole() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "ManagedPolicyTestRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "ManagedPolicyTestRole")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    // =========================================================================
    // Users
    // =========================================================================

    @Test
    @Order(10)
    void createUser() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", "test-user")
            .formParam("Path", "/")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateUserResponse.CreateUserResult.User.UserName", equalTo("test-user"))
            .body("CreateUserResponse.CreateUserResult.User.Path", equalTo("/"))
            .body("CreateUserResponse.CreateUserResult.User.UserId", startsWith("AIDA"))
            .body("CreateUserResponse.CreateUserResult.User.Arn",
                    equalTo("arn:aws:iam::000000000000:user/test-user"));
    }

    @Test
    @Order(11)
    void createUserDuplicateReturns409() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", "test-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(12)
    void getUser() {
        given()
            .formParam("Action", "GetUser")
            .formParam("UserName", "test-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetUserResponse.GetUserResult.User.UserName", equalTo("test-user"));
    }

    @Test
    @Order(13)
    void listUsers() {
        given()
            .formParam("Action", "ListUsers")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListUsersResponse.ListUsersResult.Users.member.UserName",
                    hasItem("test-user"));
    }

    @Test
    @Order(14)
    void tagAndListUserTags() {
        given()
            .formParam("Action", "TagUser")
            .formParam("UserName", "test-user")
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "test")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ListUserTags")
            .formParam("UserName", "test-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListUserTagsResponse.ListUserTagsResult.Tags.member.Key", equalTo("env"));
    }

    // =========================================================================
    // Roles
    // =========================================================================

    @Test
    @Order(20)
    void createRole() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "TestRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("Description", "Integration test role")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateRoleResponse.CreateRoleResult.Role.RoleName", equalTo("TestRole"))
            .body("CreateRoleResponse.CreateRoleResult.Role.RoleId", startsWith("AROA"))
            .body("CreateRoleResponse.CreateRoleResult.Role.Arn",
                    equalTo("arn:aws:iam::000000000000:role/TestRole"))
            .body("CreateRoleResponse.CreateRoleResult.Role.Description", equalTo("Integration test role"));
    }

    @Test
    @Order(21)
    void getRole() {
        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "TestRole")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetRoleResponse.GetRoleResult.Role.RoleName", equalTo("TestRole"));
    }

    @Test
    @Order(22)
    void listRoles() {
        given()
            .formParam("Action", "ListRoles")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListRolesResponse.ListRolesResult.Roles.member.RoleName",
                    equalTo("TestRole"));
    }

    @Test
    @Order(23)
    void iamCreateRoleHonoursTwelveDigitAccessKey() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "TenantRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=123456789012/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateRoleResponse.CreateRoleResult.Role.RoleName", equalTo("TenantRole"))
            .body("CreateRoleResponse.CreateRoleResult.Role.Arn",
                    equalTo("arn:aws:iam::123456789012:role/TenantRole"));
    }

    @Test
    @Order(24)
    void iamGetRoleHonoursTwelveDigitAccessKey() {
        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "TenantRole")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=123456789012/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetRoleResponse.GetRoleResult.Role.RoleName", equalTo("TenantRole"))
            .body("GetRoleResponse.GetRoleResult.Role.Arn",
                    equalTo("arn:aws:iam::123456789012:role/TenantRole"));
    }

    // =========================================================================
    // Managed Policies
    // =========================================================================

    @Test
    @Order(30)
    void createPolicy() {
        createdPolicyArn = given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", "TestPolicy")
            .formParam("Path", "/")
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .formParam("Description", "Test managed policy")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreatePolicyResponse.CreatePolicyResult.Policy.PolicyName", equalTo("TestPolicy"))
            .body("CreatePolicyResponse.CreatePolicyResult.Policy.PolicyId", startsWith("ANPA"))
            .body("CreatePolicyResponse.CreatePolicyResult.Policy.DefaultVersionId", equalTo("v1"))
        .extract()
            .path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    @Test
    @Order(31)
    void getPolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", createdPolicyArn)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName", equalTo("TestPolicy"));
    }

    @Test
    @Order(32)
    void attachRolePolicyAndList() {
        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "TestRole")
            .formParam("PolicyArn", createdPolicyArn)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", "TestRole")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.AttachedPolicies.member.PolicyName",
                    equalTo("TestPolicy"));
    }

    @Test
    @Order(33)
    void putAndGetRoleInlinePolicy() {
        given()
            .formParam("Action", "PutRolePolicy")
            .formParam("RoleName", "TestRole")
            .formParam("PolicyName", "inline-logging")
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\"}")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetRolePolicy")
            .formParam("RoleName", "TestRole")
            .formParam("PolicyName", "inline-logging")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetRolePolicyResponse.GetRolePolicyResult.PolicyName", equalTo("inline-logging"));
    }

    // =========================================================================
    // Access Keys
    // =========================================================================

    @Test
    @Order(40)
    void createAndListAccessKeys() {
        given()
            .formParam("Action", "CreateAccessKey")
            .formParam("UserName", "test-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId",
                    startsWith("AKIA"))
            .body("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey",
                    notNullValue())
            .body("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.Status",
                    equalTo("Active"));

        given()
            .formParam("Action", "ListAccessKeys")
            .formParam("UserName", "test-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member.UserName",
                    equalTo("test-user"));
    }

    // =========================================================================
    // Groups
    // =========================================================================

    @Test
    @Order(50)
    void createGroupAndAddUser() {
        given()
            .formParam("Action", "CreateGroup")
            .formParam("GroupName", "test-group")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateGroupResponse.CreateGroupResult.Group.GroupName", equalTo("test-group"))
            .body("CreateGroupResponse.CreateGroupResult.Group.GroupId", startsWith("AGPA"));

        given()
            .formParam("Action", "AddUserToGroup")
            .formParam("GroupName", "test-group")
            .formParam("UserName", "test-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetGroup")
            .formParam("GroupName", "test-group")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetGroupResponse.GetGroupResult.Group.GroupName", equalTo("test-group"))
            .body("GetGroupResponse.GetGroupResult.Users.member.UserName",
                    equalTo("test-user"));
    }

    // =========================================================================
    // Instance Profiles
    // =========================================================================

    @Test
    @Order(60)
    void createInstanceProfileAndAddRole() {
        // Detach policy from role first so we can test cleanly
        given()
            .formParam("Action", "CreateInstanceProfile")
            .formParam("InstanceProfileName", "test-profile")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.InstanceProfileName",
                    equalTo("test-profile"))
            .body("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.InstanceProfileId",
                    startsWith("AIPA"));

        given()
            .formParam("Action", "AddRoleToInstanceProfile")
            .formParam("InstanceProfileName", "test-profile")
            .formParam("RoleName", "TestRole")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetInstanceProfile")
            .formParam("InstanceProfileName", "test-profile")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetInstanceProfileResponse.GetInstanceProfileResult.InstanceProfile.InstanceProfileName",
                    equalTo("test-profile"))
            .body("GetInstanceProfileResponse.GetInstanceProfileResult.InstanceProfile.Roles.member.RoleName",
                    equalTo("TestRole"));
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Test
    @Order(70)
    void getUserNotFoundReturns404() {
        given()
            .formParam("Action", "GetUser")
            .formParam("UserName", "nonexistent-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(71)
    void getRoleNotFoundReturns404() {
        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "nonexistent-role")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(72)
    void unsupportedIamActionReturns400() {
        given()
            .formParam("Action", "UnknownIamAction")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnsupportedOperation"));
    }
}
