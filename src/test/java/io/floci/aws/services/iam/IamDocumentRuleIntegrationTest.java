package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Three rules AWS holds a policy document to that no write here read: an identity policy may not
 * name a principal, and a trust policy has to name one and may only grant STS's actions. All three
 * are 400 {@code MalformedPolicyDocument} with the sentence recorded for the rule, and all three
 * are applied before the document is stored.
 *
 * <p>{@code docs/services/iam.md} carries the recordings and the places this reading is
 * deliberately narrower than AWS.
 */
@QuarkusTest
class IamDocumentRuleIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String WITH_PRINCIPAL = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},"
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static final String IDENTITY = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static final String TRUST = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    private static final String TRUST_WITHOUT_PRINCIPAL = "{\"Version\":\"2012-10-17\","
            + "\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"sts:AssumeRole\"}]}";

    private static final String TRUST_WITH_FOREIGN_ACTION = "{\"Version\":\"2012-10-17\","
            + "\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},"
            + "\"Action\":\"s3:GetObject\"}]}";

    private static final String TRUST_EXCLUDING_PRINCIPALS = "{\"Version\":\"2012-10-17\","
            + "\"Statement\":[{\"Effect\":\"Deny\","
            + "\"NotPrincipal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    private static final String TRUST_WITH_NULL_PRINCIPAL = "{\"Version\":\"2012-10-17\","
            + "\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":null,"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    private static final String IDENTITY_EXCLUDING_PRINCIPALS = "{\"Version\":\"2012-10-17\","
            + "\"Statement\":[{\"Effect\":\"Deny\","
            + "\"NotPrincipal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},"
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static final String NO_PRINCIPAL = "Missing required field Principal";
    private static final String FOREIGN_ACTION =
            "AssumeRole policy may only specify STS AssumeRole actions.";
    private static final String IDENTITY_PRINCIPAL = "Policy document should not specify a principal.";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static void expectMalformed(RequestSpecification request, String message) {
        request.when().post("/").then().statusCode(400)
                .body(CODE, equalTo("MalformedPolicyDocument"))
                .body(MESSAGE, equalTo(message));
    }

    private String createUser() {
        String userName = unique("document-rule-user");
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200));
        return userName;
    }

    private String createRole() {
        return createRoleWith(unique("document-rule-role"), TRUST);
    }

    /** A role of {@code roleName} under {@code trustPolicy}, registered for removal. */
    private String createRoleWith(String roleName, String trustPolicy) {
        iam("CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", trustPolicy)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(200));
        return roleName;
    }

    /** A managed identity policy carrying a principal is refused and nothing is stored. */
    @Test
    void aManagedIdentityPolicyMayNotNameAPrincipal() {
        String policyName = unique("document-rule-policy");
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", policyName)
                .formParam("PolicyDocument", WITH_PRINCIPAL), IDENTITY_PRINCIPAL);

        iam("GetPolicy")
                .formParam("PolicyArn", "arn:aws:iam::000000000000:policy/" + policyName)
                .when().post("/").then().statusCode(404);
    }

    /** The inline writes answer the same rule, on all three principal types. */
    @Test
    void theInlineWritesMayNotNameAPrincipalEither() {
        expectMalformed(iam("PutUserPolicy").formParam("UserName", createUser())
                .formParam("PolicyName", "inline").formParam("PolicyDocument", WITH_PRINCIPAL),
                IDENTITY_PRINCIPAL);
        expectMalformed(iam("PutRolePolicy").formParam("RoleName", createRole())
                .formParam("PolicyName", "inline").formParam("PolicyDocument", WITH_PRINCIPAL),
                IDENTITY_PRINCIPAL);

        String groupName = unique("document-rule-group");
        iam("CreateGroup").formParam("GroupName", groupName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteGroup").formParam("GroupName", groupName)
                .when().post("/").then().statusCode(200));
        expectMalformed(iam("PutGroupPolicy").formParam("GroupName", groupName)
                .formParam("PolicyName", "inline").formParam("PolicyDocument", WITH_PRINCIPAL),
                IDENTITY_PRINCIPAL);
    }

    /** A trust policy has to name a principal, on the create and on the update alike. */
    @Test
    void aTrustPolicyHasToNameAPrincipal() {
        expectMalformed(iam("CreateRole").formParam("RoleName", unique("document-rule-trust"))
                .formParam("AssumeRolePolicyDocument", TRUST_WITHOUT_PRINCIPAL), NO_PRINCIPAL);
        expectMalformed(iam("UpdateAssumeRolePolicy").formParam("RoleName", createRole())
                .formParam("PolicyDocument", TRUST_WITHOUT_PRINCIPAL), NO_PRINCIPAL);
    }

    /** And may only grant STS's actions, which the namespace decides. */
    @Test
    void aTrustPolicyMayOnlyGrantStsActions() {
        expectMalformed(iam("CreateRole").formParam("RoleName", unique("document-rule-foreign"))
                .formParam("AssumeRolePolicyDocument", TRUST_WITH_FOREIGN_ACTION), FOREIGN_ACTION);
        expectMalformed(iam("UpdateAssumeRolePolicy").formParam("RoleName", createRole())
                .formParam("PolicyDocument", TRUST_WITH_FOREIGN_ACTION), FOREIGN_ACTION);
    }

    /**
     * Neither element naming a principal may stand in for {@code Principal}, since the IAM
     * reference says neither policy may carry {@code NotPrincipal} at all, and an element that
     * names nothing is the element absent: both are the recorded refusal of a trust policy with no
     * principal, and in an identity policy the exclusion is the recorded refusal of a principal.
     */
    @Test
    void neitherExclusionNorAnEmptyPrincipalNamesOne() {
        expectMalformed(iam("CreateRole").formParam("RoleName", unique("document-rule-excluding"))
                .formParam("AssumeRolePolicyDocument", TRUST_EXCLUDING_PRINCIPALS), NO_PRINCIPAL);
        expectMalformed(iam("CreateRole").formParam("RoleName", unique("document-rule-null"))
                .formParam("AssumeRolePolicyDocument", TRUST_WITH_NULL_PRINCIPAL), NO_PRINCIPAL);
        expectMalformed(iam("UpdateAssumeRolePolicy").formParam("RoleName", createRole())
                .formParam("PolicyDocument", TRUST_WITH_NULL_PRINCIPAL), NO_PRINCIPAL);
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", unique("document-rule-excl"))
                .formParam("PolicyDocument", IDENTITY_EXCLUDING_PRINCIPALS), IDENTITY_PRINCIPAL);
    }

    /**
     * The places this reading stays narrower than AWS on purpose, so none of them can refuse a
     * document AWS accepts: a statement excluding actions grants the assume actions among the
     * rest, any action of the STS namespace passes whether or not this emulator models it, and the
     * namespace is read whatever case the document spells it in, as AWS reads an action.
     */
    @Test
    void theNarrowReadingsAreAccepted() {
        String sessionActions = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
                + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\",\"sts:SetSourceIdentity\"]}]}";
        createRoleWith(unique("document-rule-session"), sessionActions);

        String excludingActions = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
                + "\"NotAction\":\"s3:GetObject\"}]}";
        createRoleWith(unique("document-rule-excluding-actions"), excludingActions);

        String otherCase = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
                + "\"Action\":[\"STS:AssumeRole\",\"Sts:TagSession\"]}]}";
        createRoleWith(unique("document-rule-case"), otherCase);
    }

    /** The control: the documents every other test here uses are still stored. */
    @Test
    void validDocumentsAreUntouched() {
        iam("PutUserPolicy").formParam("UserName", createUser())
                .formParam("PolicyName", "inline").formParam("PolicyDocument", IDENTITY)
                .when().post("/").then().statusCode(200);
        createRole();
    }
}
