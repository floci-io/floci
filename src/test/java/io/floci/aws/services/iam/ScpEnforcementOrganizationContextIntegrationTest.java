package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.OrganizationsFixture;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The organization context a real request carries, which is what the policies keyed on it decide
 * by.
 *
 * <p>Each of the three keys is "included in the request context only if the principal is a member
 * of an organization", and the guide ties them to exactly these policies: "because these keys are
 * populated for you, SCPs and other policies that use principal or organization conditions are
 * evaluated correctly". So an SCP attached to the organizational unit holding an account and keyed
 * on the organization above it decides that account's requests, where a key no value reaches
 * decides nothing, and an identity policy keyed on the same key decides a user's.
 *
 * <p>Each case attaches a policy keyed on another organization first. That one is populated and
 * does not match, which is what separates a key carrying the wrong value from a key carrying none:
 * both leave the request allowed, and only the second policy, keyed on the organization the
 * account really is in, can tell them apart.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ScpEnforcementOrganizationContextIntegrationTest {

    /** Its own management account, so no other class under this profile meets this organization. */
    private static final String MANAGEMENT = "585858585858";
    /** An account in no organization at all, for the keys that are then absent. */
    private static final String OUTSIDE = "171717171717";

    private static final String GUARDED = "organizations:DescribeOrganization";
    private static final String DENIED_CODE = "AccessDeniedException";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final OrganizationsFixture organizations = new OrganizationsFixture();
    private final List<Runnable> detachments = new ArrayList<>();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void removeWhatWasAttached() {
        for (Runnable detach : detachments.reversed()) {
            detach.run();
        }
        detachments.clear();
        organizations.cleanup();
    }

    /** One organization with SCPs enabled, one OU under its root, one member account inside it. */
    private record Hierarchy(String organizationId, String rootId, String unitId, String accountId) {

        /** What the account's principals carry: the path down to the OU holding the account. */
        String path() {
            return organizationId + "/" + rootId + "/" + unitId + "/";
        }
    }

    @Test
    void anScpKeyedOnTheOrganizationIdDecidesARealRequestFromItsAccounts() {
        Hierarchy organization = hierarchy();

        attachDeny(organization, "aws:PrincipalOrgID", "o-notthisone");
        describeOrganization(organization.accountId()).then().statusCode(200);

        attachDeny(organization, "aws:PrincipalOrgID", organization.organizationId());
        describeOrganization(organization.accountId()).then().statusCode(400)
                .body(containsString(DENIED_CODE));
    }

    /**
     * The other two keys reach the same request. The management account is named for every account
     * in the organization, and the path a principal carries ends at the organizational unit holding
     * its account, which is the value the key's own published condition compares.
     */
    @Test
    void theManagementAccountAndThePathDecideTheSameRequest() {
        Hierarchy organization = hierarchy();

        attachDeny(organization, "aws:PrincipalOrgMasterAccountId", "919191919191");
        describeOrganization(organization.accountId()).then().statusCode(200);

        String onTheManagementAccount = attachDeny(organization,
                "aws:PrincipalOrgMasterAccountId", MANAGEMENT);
        describeOrganization(organization.accountId()).then().statusCode(400)
                .body(containsString(DENIED_CODE));
        detach(organization, onTheManagementAccount);

        // Allowed again, so what denied it was that policy and not the organization itself.
        describeOrganization(organization.accountId()).then().statusCode(200);

        attachDeny(organization, "aws:PrincipalOrgPaths", organization.path());
        describeOrganization(organization.accountId()).then().statusCode(400)
                .body(containsString(DENIED_CODE));
    }

    /**
     * An identity policy reads the keys as well, so a user of a member account is decided by the
     * organization it is in, and a user of an account in no organization carries none of the three:
     * the same document leaves its request allowed, a condition on an absent key matching nothing.
     */
    @Test
    void aUsersOwnPolicyReadsThemAndAnAccountInNoOrganizationCarriesNone() {
        Hierarchy organization = hierarchy();
        String document = allowGetUserDenyingOrganization(organization.organizationId());

        String denied = userWithPolicy(organization.accountId(), "org-user", document);
        getUser(denied).then().statusCode(403);

        String outside = userWithPolicy(OUTSIDE, "outside-user", document);
        getUser(outside).then().statusCode(200);
    }

    /** One organization, SCPs enabled on its root, and a member account in an OU under it. */
    private Hierarchy hierarchy() {
        organizations.request(MANAGEMENT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        String organizationId = organizations.request(MANAGEMENT, "DescribeOrganization", "{}")
                .post("/").then().statusCode(200).extract().jsonPath().getString("Organization.Id");
        String rootId = organizations.request(MANAGEMENT, "ListRoots", "{}")
                .post("/").then().statusCode(200).extract().jsonPath().getString("Roots[0].Id");
        // A new root has no policy types enabled, and FullAWSAccess is already attached, so
        // enabling them denies nothing on its own.
        organizations.request(MANAGEMENT, "EnablePolicyType", "{\"RootId\":\"" + rootId
                + "\",\"PolicyType\":\"SERVICE_CONTROL_POLICY\"}").post("/").then().statusCode(200);
        String unitId = organizations.createOu(MANAGEMENT, rootId, "workloads");
        String accountId = organizations.createAccount(MANAGEMENT, "member@example.com", "member",
                unitId, rootId);
        return new Hierarchy(organizationId, rootId, unitId, accountId);
    }

    /** A deny on the guarded action, conditioned on one key, attached to the OU holding the account. */
    private String attachDeny(Hierarchy organization, String key, String value) {
        String content = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
                + "\"Action\":\"" + GUARDED + "\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"" + key + "\":\"" + value + "\"}}}]}";
        String policyId = organizations.request(MANAGEMENT, "CreatePolicy",
                "{\"Name\":\"deny-" + System.nanoTime() + "\",\"Type\":\"SERVICE_CONTROL_POLICY\","
                        + "\"Description\":\"deny on " + key + "\",\"Content\":" + quoted(content) + "}")
                .post("/").then().statusCode(200).extract().jsonPath()
                .getString("Policy.PolicySummary.Id");
        organizations.request(MANAGEMENT, "AttachPolicy", "{\"PolicyId\":\"" + policyId
                + "\",\"TargetId\":\"" + organization.unitId() + "\"}").post("/").then().statusCode(200);
        detachments.add(() -> {
            organizations.request(MANAGEMENT, "DetachPolicy", "{\"PolicyId\":\"" + policyId
                    + "\",\"TargetId\":\"" + organization.unitId() + "\"}").post("/");
            organizations.request(MANAGEMENT, "DeletePolicy",
                    "{\"PolicyId\":\"" + policyId + "\"}").post("/");
        });
        return policyId;
    }

    private void detach(Hierarchy organization, String policyId) {
        organizations.request(MANAGEMENT, "DetachPolicy", "{\"PolicyId\":\"" + policyId
                + "\",\"TargetId\":\"" + organization.unitId() + "\"}").post("/").then().statusCode(200);
    }

    private Response describeOrganization(String accountId) {
        return organizations.request(accountId, "DescribeOrganization", "{}").post("/");
    }

    /** A user of {@code accountId} holding {@code document} inline, signing with its own key. */
    private String userWithPolicy(String accountId, String name, String document) {
        String userName = name + "-" + System.nanoTime();
        String authorization = EnforcementFixtures.auth(accountId, "iam");
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), authorization, userName);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, userName,
                "read-itself", document);
        return credentials.accessKeyId();
    }

    private static Response getUser(String accessKeyId) {
        return given().header("Authorization", EnforcementFixtures.auth(accessKeyId, "iam"))
                .formParam("Action", "GetUser")
                .formParam("Version", "2010-05-08")
                .post("/");
    }

    /** Reads itself, unless the organization the request names is the one the principal is in. */
    private static String allowGetUserDenyingOrganization(String organizationId) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"iam:GetUser\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"iam:GetUser\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalOrgID\":\"" + organizationId
                + "\"}}}]}";
    }

    /** {@code value} as a JSON string literal, for a policy document carried inside one. */
    private static String quoted(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int at = 0; at < value.length(); at++) {
            char character = value.charAt(at);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                default -> quoted.append(character);
            }
        }
        return quoted.append('"').toString();
    }
}
