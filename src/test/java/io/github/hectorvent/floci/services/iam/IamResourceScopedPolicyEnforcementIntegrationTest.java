package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A policy statement naming one IAM resource has to bind to that resource and no other. Issue
 * #4979 was that almost every IAM action reached the evaluator with {@code *} as its resource, so
 * a statement naming a user, role, group, profile or policy was silently inert: nothing failed,
 * the statement was simply ignored, which is why these drive the whole path rather than the ARN
 * the builder returns.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamResourceScopedPolicyEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    /**
     * The emulator is shared with every other test class, so each fixture here is removed again:
     * a user, group, role, profile, policy or provider left behind shows up in the next class to
     * list one unscoped.
     */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource("/")
    URI baseUri;

    private static String arn(String resource) {
        return "arn:aws:iam::" + ACCOUNT_ID + ":" + resource;
    }

    private static String denying(String action, String resource) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:%s","Resource":"%s"}]}"""
                .formatted(action, resource);
    }

    @Test
    void aDenyNamingOneUserLeavesTheOthersAlone() {
        String suffix = suffix();
        String denied = "deny-" + suffix;
        String allowed = "keep-" + suffix;
        createUser(denied);
        createUser(allowed);

        String akid = userWith(denying("DeleteUser", arn("user/" + denied)));

        userIam(akid, "DeleteUser", Map.of("UserName", denied))
                .statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "DeleteUser", Map.of("UserName", allowed)).statusCode(200);
    }

    /**
     * The reference gives the resource as {@code user/${UserNameWithPath}}, so a user at
     * {@code /eng/} is only named by an ARN carrying that path. The second half is what makes this
     * discriminating: the same ARN without the path must not match.
     */
    @Test
    void theStoredPathIsPartOfTheArn() {
        String name = "pathed-" + suffix();
        createUser(name, "Path", "/eng/");

        String withPath = userWith(denying("GetUser", arn("user/eng/" + name)));
        userIam(withPath, "GetUser", Map.of("UserName", name))
                .statusCode(403).body(containsString("AccessDenied"));

        String withoutPath = userWith(denying("GetUser", arn("user/" + name)));
        userIam(withoutPath, "GetUser", Map.of("UserName", name)).statusCode(200);
    }

    /**
     * {@code AddUserToGroup} names two entities and is authorized against one. The reference gives
     * it the group as its only resource type, so a deny on the group binds and a deny on the user
     * it adds does not.
     */
    @Test
    void aGroupMembershipChangeIsAuthorizedAgainstTheGroup() {
        String suffix = suffix();
        String group = "Group-" + suffix;
        String member = "member-" + suffix;
        createGroup(group);
        createUser(member);

        String groupDenied = userWith(denying("AddUserToGroup", arn("group/" + group)));
        userIam(groupDenied, "AddUserToGroup", Map.of("GroupName", group, "UserName", member))
                .statusCode(403).body(containsString("AccessDenied"));

        String userDenied = userWith(denying("AddUserToGroup", arn("user/" + member)));
        userIam(userDenied, "AddUserToGroup", Map.of("GroupName", group, "UserName", member))
                .statusCode(200);
    }

    @Test
    void aDenyNamingOneRoleLeavesTheOthersAlone() {
        String suffix = suffix();
        String denied = "deny-role-" + suffix;
        String allowed = "keep-role-" + suffix;
        String trust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
        createRole(denied, trust);
        createRole(allowed, trust);

        String akid = userWith(denying("PutRolePolicy", arn("role/" + denied)));
        String inline = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                "Resource":"*"}]}""";

        userIam(akid, "PutRolePolicy", Map.of("RoleName", denied, "PolicyName", "p",
                "PolicyDocument", inline)).statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "PutRolePolicy", Map.of("RoleName", allowed, "PolicyName", "p",
                "PolicyDocument", inline)).statusCode(200);
    }

    /** The instance-profile half of the same shape: the profile is the resource, not the role. */
    @Test
    void aProfileRoleChangeIsAuthorizedAgainstTheProfile() {
        String suffix = suffix();
        String profile = "profile-" + suffix;
        String role = "profile-role-" + suffix;
        createInstanceProfile(profile);
        createRole(role, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""");

        String akid = userWith(denying("AddRoleToInstanceProfile", arn("instance-profile/" + profile)));
        userIam(akid, "AddRoleToInstanceProfile",
                Map.of("InstanceProfileName", profile, "RoleName", role))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /** A managed policy is named by the ARN the request already carries. */
    @Test
    void aDenyNamingOneManagedPolicyLeavesTheOthersAlone() {
        String suffix = suffix();
        String denied = "deny-policy-" + suffix;
        String allowed = "keep-policy-" + suffix;
        String document = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                "Resource":"*"}]}""";
        createManagedPolicy(denied, document);
        createManagedPolicy(allowed, document);

        String akid = userWith(denying("DeletePolicy", arn("policy/" + denied)));
        userIam(akid, "DeletePolicy", Map.of("PolicyArn", arn("policy/" + denied)))
                .statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "DeletePolicy", Map.of("PolicyArn", arn("policy/" + allowed)))
                .statusCode(200);
    }

    /**
     * A create has no stored resource to read, so its ARN comes from the request's own
     * {@code Path}. The same name at a different path is a different resource and stays allowed.
     */
    @Test
    void aCreateIsAuthorizedAgainstThePathItAsksFor() {
        String name = "created-" + suffix();
        // Created through the enforced path rather than by the admin helper, so register it here.
        cleanup.register(() -> EnforcementFixtures.removeUser(port(), adminAuth(), name));
        String akid = userWith(denying("CreateUser", arn("user/eng/" + name)));

        userIam(akid, "CreateUser", Map.of("UserName", name, "Path", "/eng/"))
                .statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "CreateUser", Map.of("UserName", name)).statusCode(200);
    }

    /**
     * A rename is authorized against both names, which UpdateUser's own reference requires: the
     * requester must have permissions "on both the source object and the target object". A deny on
     * the destination alone is enough to refuse it.
     */
    @Test
    void aRenameIsRefusedWhenOnlyTheDestinationIsDenied() {
        String suffix = suffix();
        String from = "rename-from-" + suffix;
        String to = "rename-to-" + suffix;
        createUser(from);
        // A rename moves the user, so the name that survives is the destination's.
        cleanup.register(() -> EnforcementFixtures.removeUser(port(), adminAuth(), to));

        String akid = userWith(denying("UpdateUser", arn("user/" + to)));
        userIam(akid, "UpdateUser", Map.of("UserName", from, "NewUserName", to))
                .statusCode(403).body(containsString("AccessDenied"));

        // With both names allowed it goes through, so the extra resource is not a blanket no.
        String allowed = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"}]}""");
        userIam(allowed, "UpdateUser", Map.of("UserName", from, "NewUserName", to)).statusCode(200);
    }

    /** A resource-scoped Allow grants that resource only, which is the other direction. */
    @Test
    void aResourceScopedAllowGrantsThatUserOnly() {
        String suffix = suffix();
        String granted = "allow-" + suffix;
        String other = "other-" + suffix;
        createUser(granted);
        createUser(other);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:GetUser","Resource":"%s"}]}"""
                .formatted(arn("user/" + granted)));

        userIam(akid, "GetUser", Map.of("UserName", granted)).statusCode(200);
        userIam(akid, "GetUser", Map.of("UserName", other))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * The actions whose {@code UserName} is optional act on the user who signed the request, so a
     * statement scoped to that user has to reach them. Before this, such a call arrived with
     * {@code *} as its resource and the statement was ignored.
     */
    @Test
    void anOmittedUserNameNamesTheSigningUser() {
        String user = "self-" + suffix();
        createUser(user);
        adminIam("PutUserPolicy", Map.of("UserName", user, "PolicyName", "p",
                "PolicyDocument", denying("GetUser", arn("user/" + user)))).statusCode(200);
        String akid = adminIam("CreateAccessKey", Map.of("UserName", user)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey"
                        + ".AccessKeyId");

        // No UserName: the request is about the signer, and the deny names exactly that user.
        userIam(akid, "GetUser", Map.of()).statusCode(403).body(containsString("AccessDenied"));
        // Naming someone else still goes through, so the deny bound to the signer and not to all.
        String other = "other-" + suffix();
        createUser(other);
        userIam(akid, "GetUser", Map.of("UserName", other)).statusCode(200);
    }

    /** A virtual MFA device is named by the serial number the request carries. */
    @Test
    void aDenyNamingOneVirtualMfaDeviceLeavesTheOthersAlone() {
        String suffix = suffix();
        String denied = "deny-mfa-" + suffix;
        String allowed = "keep-mfa-" + suffix;
        createVirtualMfaDevice(denied);
        createVirtualMfaDevice(allowed);

        String akid = userWith(denying("DeleteVirtualMFADevice", arn("mfa/" + denied)));
        userIam(akid, "DeleteVirtualMFADevice", Map.of("SerialNumber", arn("mfa/" + denied)))
                .statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "DeleteVirtualMFADevice", Map.of("SerialNumber", arn("mfa/" + allowed)))
                .statusCode(200);
    }

    /** A SAML provider is named by the ARN the request carries. */
    @Test
    void aDenyNamingOneSamlProviderLeavesTheOthersAlone() {
        String suffix = suffix();
        String denied = "deny-saml-" + suffix;
        String allowed = "keep-saml-" + suffix;
        createSamlProvider(denied);
        createSamlProvider(allowed);

        String akid = userWith(denying("GetSAMLProvider", arn("saml-provider/" + denied)));
        userIam(akid, "GetSAMLProvider",
                Map.of("SAMLProviderArn", arn("saml-provider/" + denied)))
                .statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "GetSAMLProvider",
                Map.of("SAMLProviderArn", arn("saml-provider/" + allowed))).statusCode(200);
    }

    /** An OIDC provider's ARN carries its URL without the scheme, which is how IAM stores it. */
    @Test
    void aDenyNamingOneOidcProviderLeavesTheOthersAlone() {
        String suffix = suffix();
        String denied = "deny-oidc-" + suffix + ".example.com";
        String allowed = "keep-oidc-" + suffix + ".example.com";
        createOidcProvider(denied);
        createOidcProvider(allowed);

        String akid = userWith(denying("GetOpenIDConnectProvider", arn("oidc-provider/" + denied)));
        userIam(akid, "GetOpenIDConnectProvider",
                Map.of("OpenIDConnectProviderArn", arn("oidc-provider/" + denied)))
                .statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "GetOpenIDConnectProvider",
                Map.of("OpenIDConnectProviderArn", arn("oidc-provider/" + allowed)))
                .statusCode(200);
    }

    /** The shape {@code SAMLProviderLifecycleIntegrationTest} already establishes as acceptable. */
    private static String samlMetadata(String entityId) {
        return "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + entityId + "\"><md:IDPSSODescriptor><md:KeyDescriptor use=\"signing\">"
                + "<ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data>"
                + "<ds:X509Certificate>c2NvcGVk</ds:X509Certificate></ds:X509Data></ds:KeyInfo>"
                + "</md:KeyDescriptor></md:IDPSSODescriptor></md:EntityDescriptor>";
    }

    private String createUser(String name, String... extraParams) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("UserName", name);
        for (int i = 0; i < extraParams.length; i += 2) {
            params.put(extraParams[i], extraParams[i + 1]);
        }
        cleanup.register(() -> EnforcementFixtures.removeUser(port(), adminAuth(), name));
        return adminIam("CreateUser", params).statusCode(200).extract().path(
                "CreateUserResponse.CreateUserResult.User.Arn");
    }

    private void createGroup(String name) {
        cleanup.register(() -> EnforcementFixtures.removeGroup(port(), adminAuth(), name));
        adminIam("CreateGroup", Map.of("GroupName", name)).statusCode(200);
    }

    private void createRole(String name, String trustPolicy) {
        cleanup.register(() -> EnforcementFixtures.removeRole(port(), adminAuth(), name));
        adminIam("CreateRole", Map.of("RoleName", name,
                "AssumeRolePolicyDocument", trustPolicy)).statusCode(200);
    }

    private void createInstanceProfile(String name) {
        cleanup.register(() ->
                EnforcementFixtures.removeInstanceProfile(port(), adminAuth(), name));
        adminIam("CreateInstanceProfile", Map.of("InstanceProfileName", name)).statusCode(200);
    }

    private void createManagedPolicy(String name, String document) {
        cleanup.register(() ->
                EnforcementFixtures.removePolicy(port(), adminAuth(), arn("policy/" + name)));
        adminIam("CreatePolicy", Map.of("PolicyName", name, "PolicyDocument", document))
                .statusCode(200);
    }

    private void createVirtualMfaDevice(String name) {
        cleanup.register(() -> EnforcementFixtures.removeByArn(port(), adminAuth(),
                "DeleteVirtualMFADevice", "SerialNumber", arn("mfa/" + name)));
        adminIam("CreateVirtualMFADevice", Map.of("VirtualMFADeviceName", name)).statusCode(200);
    }

    private void createSamlProvider(String name) {
        cleanup.register(() -> EnforcementFixtures.removeByArn(port(), adminAuth(),
                "DeleteSAMLProvider", "SAMLProviderArn", arn("saml-provider/" + name)));
        adminIam("CreateSAMLProvider", Map.of("Name", name, "SAMLMetadataDocument",
                samlMetadata("https://idp.example.test/" + name))).statusCode(200);
    }

    private void createOidcProvider(String host) {
        cleanup.register(() -> EnforcementFixtures.removeByArn(port(), adminAuth(),
                "DeleteOpenIDConnectProvider", "OpenIDConnectProviderArn",
                arn("oidc-provider/" + host)));
        adminIam("CreateOpenIDConnectProvider", Map.of("Url", "https://" + host,
                "ClientIDList.member.1", "sts.amazonaws.com")).statusCode(200);
    }

    private int port() {
        return baseUri.getPort();
    }

    private static String adminAuth() {
        return auth(ACCOUNT_ID);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** An IAM user in {@link #ACCOUNT_ID} carrying {@code policy} inline. */
    private String userWith(String policy) {
        String user = "scoped-" + suffix();
        createUser(user);
        adminIam("PutUserPolicy", Map.of("UserName", user, "PolicyName", "p",
                "PolicyDocument", policy)).statusCode(200);
        return adminIam("CreateAccessKey", Map.of("UserName", user)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static ValidatableResponse adminIam(String action, Map<String, String> params) {
        return iamCall(ACCOUNT_ID, action, params);
    }

    private static ValidatableResponse userIam(String akid, String action,
                                               Map<String, String> params) {
        return iamCall(akid, action, params);
    }

    private static ValidatableResponse iamCall(String akid, String action,
                                               Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", auth(akid))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String auth(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260919/" + REGION + "/iam"
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
