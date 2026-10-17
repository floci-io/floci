package io.github.hectorvent.floci.testing;

import io.restassured.filter.Filter;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;

/**
 * Takes an IAM user or an S3 bucket that a test created back out of the shared application, and
 * makes the users, roles and role sessions an enforcement test signs as ({@link #createUser},
 * {@link #createRole}, {@link #assumeRole}, {@link #auth}) and the SNS topics it publishes to
 * ({@link #createTopic}), registering each one's removal as it goes. A {@code @QuarkusTest} shares
 * one application with every other test class, so a fixture left behind outlives the class that
 * made it and is visible to the next class that lists users or buckets unscoped. Registered with
 * {@link PartitionCleanup} as each fixture is made.
 *
 * <p>Both read back what is attached rather than taking it as an argument, and both return
 * quietly when the thing is not there. That is what makes them safe to register before the create
 * runs: a setup that fails part way through still has the whole fixture taken away, and one that
 * created nothing leaves these with nothing to do.
 *
 * <p>The port is passed in because Quarkus points RestAssured at the application only for the
 * duration of a test method and resets the port afterwards, so a teardown has to say where the
 * application is. The test port is random, which leaves an injected {@code @TestHTTPResource}
 * URI as the only place to read it from.
 */
public final class EnforcementFixtures {

    private EnforcementFixtures() {
        // Utility class, prevent instantiation
    }

    /**
     * Deletes an IAM user, and first everything IAM wants gone before it will: inline policies,
     * access keys, group memberships and attached managed policies. The IAM API reference lists
     * all four among the items to remove "before attempting to delete a user", and Floci answers
     * {@code DeleteConflict} for each of them.
     *
     * @param authorization an IAM-scoped header. Signing with an account id reaches that
     *                      account's namespace as its root stand-in, which is how a user outside
     *                      the default account is removed.
     */
    public static void removeUser(int port, String authorization, String userName) {
        if (iam(port, authorization, "GetUser", "UserName", userName).extract().statusCode() == 404) {
            return;
        }
        for (String policyName : iam(port, authorization, "ListUserPolicies", "UserName", userName)
                .statusCode(200).extract().xmlPath()
                .getList("ListUserPoliciesResponse.ListUserPoliciesResult.PolicyNames.member",
                        String.class)) {
            iam(port, authorization, "DeleteUserPolicy", "UserName", userName,
                    "PolicyName", policyName).statusCode(200);
        }
        for (String accessKeyId : iam(port, authorization, "ListAccessKeys", "UserName", userName)
                .statusCode(200).extract().xmlPath()
                .getList("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member"
                        + ".AccessKeyId", String.class)) {
            iam(port, authorization, "DeleteAccessKey", "UserName", userName,
                    "AccessKeyId", accessKeyId).statusCode(200);
        }
        for (String groupName : iam(port, authorization, "ListGroupsForUser",
                "UserName", userName).statusCode(200).extract().xmlPath()
                .getList("ListGroupsForUserResponse.ListGroupsForUserResult.Groups.member"
                        + ".GroupName", String.class)) {
            iam(port, authorization, "RemoveUserFromGroup", "GroupName", groupName,
                    "UserName", userName).statusCode(200);
        }
        for (String policyArn : iam(port, authorization, "ListAttachedUserPolicies",
                "UserName", userName).statusCode(200).extract().xmlPath()
                .getList("ListAttachedUserPoliciesResponse.ListAttachedUserPoliciesResult"
                        + ".AttachedPolicies.member.PolicyArn", String.class)) {
            iam(port, authorization, "DetachUserPolicy", "UserName", userName,
                    "PolicyArn", policyArn).statusCode(200);
        }
        iam(port, authorization, "DeleteUser", "UserName", userName).statusCode(200);
    }

    /** The access key {@link #createUser} issued, with the secret a request signer needs. */
    public record UserCredentials(String accessKeyId, String secretAccessKey) {
    }

    /**
     * Creates an IAM user holding one access key, registering its {@link #removeUser} with
     * {@code cleanup} before the create runs, so the user and its key go after the test whatever
     * the test does with them.
     *
     * @param authorization an IAM-scoped header for the account the user belongs to, as for
     *                      {@link #removeUser}, which is handed the same one
     */
    public static UserCredentials createUser(PartitionCleanup cleanup, int port, String authorization,
                                             String userName) {
        cleanup.register(() -> removeUser(port, authorization, userName));
        iam(port, authorization, "CreateUser", "UserName", userName).statusCode(200);
        XmlPath key = iam(port, authorization, "CreateAccessKey", "UserName", userName)
                .statusCode(200).extract().xmlPath();
        return new UserCredentials(
                key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"));
    }

    /**
     * Creates a role under {@code path} that the root of its own {@code account} may assume, with
     * {@code policy} inline, registering its {@link #removeRole} with {@code cleanup} before the
     * create runs, and returns the role's ARN.
     */
    public static String createRole(PartitionCleanup cleanup, int port, String account, String path,
                                    String roleName, String policy) {
        String authorization = auth(account, "iam");
        cleanup.register(() -> removeRole(port, authorization, roleName));
        iam(port, authorization, "CreateRole", "RoleName", roleName, "Path", path,
                "AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}"""
                        .formatted(account)).statusCode(200);
        iam(port, authorization, "PutRolePolicy", "RoleName", roleName, "PolicyName", "p",
                "PolicyDocument", policy).statusCode(200);
        return "arn:aws:iam::" + account + ":role" + path + roleName;
    }

    /** The temporary credentials of a role session, which a request signs with and sends as its token. */
    public record Session(String accessKeyId, String sessionToken) {
    }

    /**
     * Assumes {@code roleArn} as {@code callerAccessKeyId}, in a session named {@code s}, and returns
     * the session's credentials. The role's trust policy has to admit the caller.
     */
    public static Session assumeRole(int port, String callerAccessKeyId, String roleArn) {
        XmlPath credentials = query(port, auth(callerAccessKeyId, "sts"), "AssumeRole",
                "RoleArn", roleArn, "RoleSessionName", "s")
                .statusCode(200).extract().xmlPath();
        return new Session(
                credentials.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId"),
                credentials.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken"));
    }

    /**
     * A SigV4 {@code Authorization} header naming {@code accessKeyId}, scoped to {@code service} in
     * {@code us-east-1}. The signature is a placeholder, so under a profile that verifies signatures
     * the request has to be signed for real instead.
     */
    public static String auth(String accessKeyId, String service) {
        return auth(accessKeyId, "us-east-1", service);
    }

    /** A placeholder signature scoped to the caller's selected region and service. */
    public static String auth(String accessKeyId, String region, String service) {
        return auth(accessKeyId, "20260629", region, service);
    }

    /** A placeholder signature retaining the date selected by an existing request fixture. */
    public static String auth(String accessKeyId, String date, String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + date + "/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    /**
     * Deletes a managed policy, and first the versions and attachments IAM wants gone before it
     * will. Returns quietly when the policy is already absent, so a caller can register the
     * removal before creating it.
     */
    public static void removePolicy(int port, String authorization, String policyArn) {
        if (iam(port, authorization, "GetPolicy", "PolicyArn", policyArn)
                .extract().statusCode() == 404) {
            return;
        }
        iam(port, authorization, "DeletePolicy", "PolicyArn", policyArn).statusCode(200);
    }

    /**
     * Deletes a role, and first the policies it carries: Floci answers {@code DeleteConflict},
     * "must detach all policies first", for a role holding either an inline or a managed one.
     */
    public static void removeRole(int port, String authorization, String roleName) {
        if (iam(port, authorization, "GetRole", "RoleName", roleName)
                .extract().statusCode() == 404) {
            return;
        }
        for (String policyName : iam(port, authorization, "ListRolePolicies", "RoleName", roleName)
                .statusCode(200).extract().xmlPath()
                .getList("ListRolePoliciesResponse.ListRolePoliciesResult.PolicyNames.member",
                        String.class)) {
            iam(port, authorization, "DeleteRolePolicy", "RoleName", roleName,
                    "PolicyName", policyName).statusCode(200);
        }
        for (String policyArn : iam(port, authorization, "ListAttachedRolePolicies",
                "RoleName", roleName).statusCode(200).extract().xmlPath()
                .getList("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult"
                        + ".AttachedPolicies.member.PolicyArn", String.class)) {
            iam(port, authorization, "DetachRolePolicy", "RoleName", roleName,
                    "PolicyArn", policyArn).statusCode(200);
        }
        iam(port, authorization, "DeleteRole", "RoleName", roleName).statusCode(200);
    }

    /**
     * Deletes an instance profile, taking any role out of it first: AWS requires the profile to
     * hold no role before it can go, and the role is a separate resource with its own removal.
     */
    public static void removeInstanceProfile(int port, String authorization, String profileName) {
        if (iam(port, authorization, "GetInstanceProfile", "InstanceProfileName", profileName)
                .extract().statusCode() == 404) {
            return;
        }
        for (String roleName : iam(port, authorization, "GetInstanceProfile",
                "InstanceProfileName", profileName).statusCode(200).extract().xmlPath()
                .getList("GetInstanceProfileResponse.GetInstanceProfileResult.InstanceProfile"
                        + ".Roles.member.RoleName", String.class)) {
            iam(port, authorization, "RemoveRoleFromInstanceProfile",
                    "InstanceProfileName", profileName, "RoleName", roleName).statusCode(200);
        }
        iam(port, authorization, "DeleteInstanceProfile",
                "InstanceProfileName", profileName).statusCode(200);
    }

    /** Deletes a group after removing its users, inline policies and managed policy attachments. */
    public static void removeGroup(int port, String authorization, String groupName) {
        if (iam(port, authorization, "GetGroup", "GroupName", groupName)
                .extract().statusCode() == 404) {
            return;
        }
        for (String userName : iam(port, authorization, "GetGroup", "GroupName", groupName)
                .statusCode(200).extract().xmlPath()
                .getList("GetGroupResponse.GetGroupResult.Users.member.UserName", String.class)) {
            iam(port, authorization, "RemoveUserFromGroup",
                    "GroupName", groupName, "UserName", userName).statusCode(200);
        }
        for (String policyName : iam(port, authorization, "ListGroupPolicies",
                "GroupName", groupName).statusCode(200).extract().xmlPath()
                .getList("ListGroupPoliciesResponse.ListGroupPoliciesResult.PolicyNames.member",
                        String.class)) {
            iam(port, authorization, "DeleteGroupPolicy", "GroupName", groupName,
                    "PolicyName", policyName).statusCode(200);
        }
        for (String policyArn : iam(port, authorization, "ListAttachedGroupPolicies",
                "GroupName", groupName).statusCode(200).extract().xmlPath()
                .getList("ListAttachedGroupPoliciesResponse.ListAttachedGroupPoliciesResult"
                        + ".AttachedPolicies.member.PolicyArn", String.class)) {
            iam(port, authorization, "DetachGroupPolicy", "GroupName", groupName,
                    "PolicyArn", policyArn).statusCode(200);
        }
        iam(port, authorization, "DeleteGroup", "GroupName", groupName).statusCode(200);
    }

    /**
     * Deletes a virtual MFA device, a SAML provider or an OIDC provider, each named by the ARN it
     * was created with. One method because the three take the same shape: an ARN parameter, a
     * delete that needs nothing removed first, and no read that distinguishes absent from denied,
     * so a delete of something already gone is accepted rather than asserted on.
     */
    public static void removeByArn(int port, String authorization, String action,
                                   String parameter, String arn) {
        iam(port, authorization, action, parameter, arn);
    }

    /**
     * Deletes an S3 bucket after all its object versions and delete markers. Repeatedly removes
     * the first page so deleting a page cannot invalidate a continuation marker. Retained or
     * locked versions are left for the caller's cleanup to report as a failure.
     *
     * @param authorization an S3-scoped header for an identity allowed to list bucket versions,
     *                      delete object versions, and delete the bucket
     */
    public static void removeBucket(int port, String authorization, String bucket) {
        removeBucket(() -> s3(port, authorization), bucket);
    }

    /**
     * {@link #removeBucket(int, String, String)} under a profile that turns on
     * {@code floci.services.s3.enforce-auth}, where S3 verifies every header signature and a
     * fixed {@code Authorization} header is refused. Each request is signed by {@code signer}.
     *
     * @param signer a request signer, such as {@code S3RequestSigner}, for an identity allowed to
     *               list bucket versions, delete object versions, and delete the bucket
     */
    public static void removeBucket(int port, Filter signer, String bucket) {
        removeBucket(() -> given().port(port).filter(signer), bucket);
    }

    private static void removeBucket(Supplier<RequestSpecification> s3, String bucket) {
        if (s3.get().when().head("/" + bucket)
                .then().extract().statusCode() == 404) {
            return;
        }
        List<ObjectVersion> previousPage = List.of();
        while (true) {
            XmlPath listing = s3.get().queryParam("versions", "")
                    .queryParam("max-keys", 10)
                    .when().get("/" + bucket)
                    .then().statusCode(200).extract().xmlPath();
            List<ObjectVersion> page = new ArrayList<>();
            addVersions(page, listing, "Version");
            addVersions(page, listing, "DeleteMarker");
            if (page.isEmpty()) {
                break;
            }
            if (page.equals(previousPage)) {
                throw new IllegalStateException("S3 fixture purge made no progress for bucket " + bucket);
            }
            for (ObjectVersion version : page) {
                String encodedKey = URLEncoder.encode(version.key(), StandardCharsets.UTF_8)
                        .replace("+", "%20").replace("%2F", "/");
                s3.get().urlEncodingEnabled(false)
                        .queryParam("versionId", version.versionId())
                        .when().delete("/" + bucket + "/" + encodedKey)
                        .then().statusCode(204);
            }
            previousPage = page;
        }
        s3.get().when().delete("/" + bucket).then().statusCode(204);
    }

    /**
     * Creates an SNS topic in the account {@code authorization} signs for, registering its deletion
     * with {@code cleanup} as soon as it exists, and returns its ARN.
     *
     * @param authorization an SNS-scoped header for the account the topic belongs to
     */
    public static String createTopic(PartitionCleanup cleanup, int port, String authorization, String name) {
        String topicArn = query(port, authorization, "CreateTopic", "Name", name)
                .statusCode(200).extract().path("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> query(port, authorization, "DeleteTopic", "TopicArn", topicArn).statusCode(200));
        return topicArn;
    }

    /**
     * Replaces a topic's access policy.
     *
     * @param authorization an SNS-scoped header for the topic's owner
     */
    public static void setTopicPolicy(int port, String authorization, String topicArn, String policy) {
        query(port, authorization, "SetTopicAttributes", "TopicArn", topicArn,
                "AttributeName", "Policy", "AttributeValue", policy).statusCode(200);
    }

    private static void addVersions(List<ObjectVersion> page, XmlPath listing, String element) {
        List<String> keys = listing.getList("ListVersionsResult." + element + ".Key", String.class);
        List<String> versionIds = listing.getList("ListVersionsResult." + element + ".VersionId", String.class);
        for (int i = 0; i < keys.size(); i++) {
            page.add(new ObjectVersion(keys.get(i), versionIds.get(i)));
        }
    }

    private record ObjectVersion(String key, String versionId) {
    }

    private static ValidatableResponse iam(int port, String authorization, String action,
                                           String... params) {
        return query(port, authorization, action, params);
    }

    /** A Query-protocol request: the action and its parameters as form fields. */
    private static ValidatableResponse query(int port, String authorization, String action,
                                             String... params) {
        RequestSpecification request = given().port(port)
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static RequestSpecification s3(int port, String authorization) {
        return given().port(port).header("Authorization", authorization);
    }
}
