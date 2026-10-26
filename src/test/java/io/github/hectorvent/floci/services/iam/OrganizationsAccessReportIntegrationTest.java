package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Organizations access report end to end, over a real organization built through the
 * Organizations API.
 *
 * <p>Runs as its own management account so it never contends with the other Organizations suites
 * for one, and each test deletes the organization it built, the Quarkus instance being shared.
 *
 * <p>Floci records no service access, so every service in a report is one principals are allowed
 * but never reached. That is the shape the reference's own example gives such a service: a
 * namespace and a zero count, with no region, entity path or last-authenticated time.
 */
@QuarkusTest
class OrganizationsAccessReportIntegrationTest {

    private static final String MANAGEMENT_ACCOUNT = "555555555555";
    private static final String MEMBER_ACCOUNT = "555555555556";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";
    /** The allow-list idiom an SCP is actually written as: allow all, then deny all but one. */
    private static final String DENY_ALL_BUT_S3 =
            "{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":["
                    + "{\\\"Effect\\\":\\\"Allow\\\",\\\"Action\\\":\\\"*\\\",\\\"Resource\\\":\\\"*\\\"},"
                    + "{\\\"Effect\\\":\\\"Deny\\\",\\\"NotAction\\\":\\\"s3:*\\\","
                    + "\\\"Resource\\\":\\\"*\\\"}]}";
    private static final String ALLOW_S3_ONLY =
            "{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\","
                    + "\\\"Action\\\":\\\"s3:*\\\",\\\"Resource\\\":\\\"*\\\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String authorization(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20261010/us-east-1/" + service + "/aws4_request, SignedHeaders=host, "
                + "Signature=abc";
    }

    private static RequestSpecification organizations(String accountId, String action, String body) {
        return given()
                .header("Authorization", authorization(accountId, "organizations"))
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }

    private static RequestSpecification iam(String accountId, String action) {
        return given().header("Authorization", authorization(accountId, "iam"))
                .formParam("Action", action);
    }

    /** Creates the organization with SCPs enabled on its root, and returns its entity path. */
    private String organizationWithScpsEnabled() {
        return organization(true);
    }

    /** The same organization with the prerequisite left off: SCPs never enabled on the root. */
    private String organizationWithoutScpsEnabled() {
        return organization(false);
    }

    private String organization(boolean enableServiceControlPolicies) {
        cleanup.register(() -> organizations(MANAGEMENT_ACCOUNT, "DeleteOrganization", "{}")
                .when().post("/").then().statusCode(200));
        String organizationId = organizations(MANAGEMENT_ACCOUNT, "CreateOrganization",
                "{\"FeatureSet\":\"ALL\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Organization.Id");
        String rootId = organizations(MANAGEMENT_ACCOUNT, "ListRoots", "{}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
        if (enableServiceControlPolicies) {
            organizations(MANAGEMENT_ACCOUNT, "EnablePolicyType",
                    "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"SERVICE_CONTROL_POLICY\"}")
                    .when().post("/").then().statusCode(200);
        }
        return organizationId + "/" + rootId;
    }

    private void attach(String policyId, String targetId) {
        organizations(MANAGEMENT_ACCOUNT, "AttachPolicy",
                "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\"" + targetId + "\"}")
                .when().post("/").then().statusCode(200);
    }

    private String createOrganizationalUnit(String name, String parentId) {
        String ouId = organizations(MANAGEMENT_ACCOUNT, "CreateOrganizationalUnit",
                "{\"Name\":\"" + name + "\",\"ParentId\":\"" + parentId + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
        // Registered after the organization's own undo so it runs before it: Organizations
        // refuses to delete an organization that still holds an OU, and the leftover would then
        // fail the next test's CreateOrganization rather than this one.
        cleanup.register(() -> organizations(MANAGEMENT_ACCOUNT, "DeleteOrganizationalUnit",
                "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
                .when().post("/").then().statusCode(200));
        return ouId;
    }

    /** The allow-list idiom over several namespaces: allow everything, then deny all but these. */
    private static String denyAllBut(String... namespaces) {
        StringBuilder notActions = new StringBuilder();
        for (String namespace : namespaces) {
            notActions.append(notActions.isEmpty() ? "" : ",")
                    .append("\\\"").append(namespace).append(":*\\\"");
        }
        return "{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":["
                + "{\\\"Effect\\\":\\\"Allow\\\",\\\"Action\\\":\\\"*\\\","
                + "\\\"Resource\\\":\\\"*\\\"},"
                + "{\\\"Effect\\\":\\\"Deny\\\",\\\"NotAction\\\":[" + notActions
                + "],\\\"Resource\\\":\\\"*\\\"}]}";
    }

    private String createScp(String name, String content) {
        return organizations(MANAGEMENT_ACCOUNT, "CreatePolicy",
                "{\"Content\":\"" + content + "\",\"Description\":\"" + name + "\","
                        + "\"Name\":\"" + name + "\",\"Type\":\"SERVICE_CONTROL_POLICY\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Policy.PolicySummary.Id");
    }

    private String generate(String entityPath, String policyId) {
        RequestSpecification request = iam(MANAGEMENT_ACCOUNT, "GenerateOrganizationsAccessReport")
                .formParam("EntityPath", entityPath);
        if (policyId != null) {
            request = request.formParam("OrganizationsPolicyId", policyId);
        }
        return request.when().post("/").then().statusCode(200)
                .extract().path("GenerateOrganizationsAccessReportResponse."
                        + "GenerateOrganizationsAccessReportResult.JobId");
    }

    private XmlPath report(String jobId, String... extraParams) {
        RequestSpecification request = iam(MANAGEMENT_ACCOUNT, "GetOrganizationsAccessReport")
                .formParam("JobId", jobId);
        for (int i = 0; i < extraParams.length; i += 2) {
            request = request.formParam(extraParams[i], extraParams[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String result(String field) {
        return "GetOrganizationsAccessReportResponse.GetOrganizationsAccessReportResult." + field;
    }

    @Test
    void aJobIdIsTheModeledLengthAndTheReportReadsBackCompleted() {
        String entityPath = organizationWithScpsEnabled();

        String jobId = generate(entityPath, null);

        // jobIDType is min 36 / max 36, so a client buffer sized to the model must fit it exactly.
        assertEquals(36, jobId.length(), "expected a 36-character job id, got: " + jobId);
        XmlPath report = report(jobId);
        assertEquals("COMPLETED", report.getString(result("JobStatus")));
        assertFalse(report.getString(result("JobCreationDate")).isBlank());
        assertFalse(report.getString(result("JobCompletionDate")).isBlank());
    }

    @Test
    void aServiceNobodyReachedCarriesAZeroCountAndNoAttemptDetails() {
        String jobId = generate(organizationWithScpsEnabled(), null);

        XmlPath report = report(jobId, "MaxItems", "1");

        assertEquals("0", report.getString(result("AccessDetails.member[0]"
                + ".TotalAuthenticatedEntities")));
        assertFalse(report.getString(result("AccessDetails.member[0].ServiceNamespace")).isBlank());
        // The reference's own example omits all three for a service with no attempt.
        assertTrue(report.getString(result("AccessDetails.member[0].LastAuthenticatedTime")) == null
                        || report.getString(result("AccessDetails.member[0]"
                        + ".LastAuthenticatedTime")).isBlank());
        assertTrue(report.getString(result("AccessDetails.member[0].Region")) == null
                || report.getString(result("AccessDetails.member[0].Region")).isBlank());
        assertTrue(report.getString(result("AccessDetails.member[0].EntityPath")) == null
                || report.getString(result("AccessDetails.member[0].EntityPath")).isBlank());
    }

    @Test
    void theCountsAreTheWholeReportAndEveryServiceIsOneNobodyUsed() {
        String jobId = generate(organizationWithScpsEnabled(), null);

        XmlPath report = report(jobId);

        String accessible = report.getString(result("NumberOfServicesAccessible"));
        assertEquals(accessible, report.getString(result("NumberOfServicesNotAccessed")));
        assertTrue(Integer.parseInt(accessible) > 100, "expected the full namespace catalog, got "
                + accessible);
    }

    @Test
    void aPolicyIdNarrowsTheReportToThatPolicy() {
        String entityPath = organizationWithScpsEnabled();
        String policyId = createScp("S3Only", ALLOW_S3_ONLY);

        XmlPath report = report(generate(entityPath, policyId));

        assertEquals("1", report.getString(result("NumberOfServicesAccessible")));
        assertEquals("s3", report.getString(result("AccessDetails.member.ServiceNamespace")));
    }

    @Test
    void anScpOnTheRootNarrowsTheReportToWhatItLeaves() {
        // The one test that fails if the report does not read the SCPs off the organization tree:
        // with only FullAWSAccess attached every service is reachable either way, so the ceiling
        // has to come from a policy this test attached.
        String entityPath = organizationWithScpsEnabled();
        attach(createScp("S3Ceiling", DENY_ALL_BUT_S3),
                entityPath.substring(entityPath.indexOf('/') + 1));

        XmlPath report = report(generate(entityPath, null));

        assertEquals("1", report.getString(result("NumberOfServicesAccessible")));
        assertEquals("s3", report.getString(result("AccessDetails.member.ServiceNamespace")));
    }

    @Test
    void theReportIsPaginatedAndSortable() {
        String jobId = generate(organizationWithScpsEnabled(), null);

        XmlPath firstPage = report(jobId, "MaxItems", "2");
        assertEquals("true", firstPage.getString(result("IsTruncated")));
        List<String> ascending = firstPage.getList(result("AccessDetails.member.ServiceNamespace"));
        assertEquals(2, ascending.size());
        assertTrue(ascending.get(0).compareTo(ascending.get(1)) < 0, ascending.toString());

        List<String> descending = report(jobId, "MaxItems", "2",
                "SortKey", "SERVICE_NAMESPACE_DESCENDING")
                .getList(result("AccessDetails.member.ServiceNamespace"));
        assertTrue(descending.get(0).compareTo(descending.get(1)) > 0, descending.toString());

        String marker = firstPage.getString(result("Marker"));
        assertFalse(marker.isBlank());
        List<String> secondPage = report(jobId, "MaxItems", "2", "Marker", marker)
                .getList(result("AccessDetails.member.ServiceNamespace"));
        assertFalse(secondPage.contains(ascending.get(0)), secondPage.toString());
    }

    @Test
    void aSortKeyOutsideTheEnumIsRejected() {
        String jobId = generate(organizationWithScpsEnabled(), null);

        iam(MANAGEMENT_ACCOUNT, "GetOrganizationsAccessReport")
                .formParam("JobId", jobId)
                .formParam("SortKey", "SERVICE_NAMESPACE")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void onlyTheManagementAccountCanReadOrGenerateAReport() {
        String entityPath = organizationWithScpsEnabled();
        String jobId = generate(entityPath, null);

        iam(MEMBER_ACCOUNT, "GenerateOrganizationsAccessReport")
                .formParam("EntityPath", entityPath)
        .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));

        iam(MEMBER_ACCOUNT, "GetOrganizationsAccessReport")
                .formParam("JobId", jobId)
        .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    @Test
    void anEntityPathTheModelRejectsIsAValidationError() {
        organizationWithScpsEnabled();

        iam(MANAGEMENT_ACCOUNT, "GenerateOrganizationsAccessReport")
                .formParam("EntityPath", "o-a1b2c3d4e5")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void anEntityPathNamingAnotherOrganizationFailsTheJobRatherThanTheCall() {
        organizationWithScpsEnabled();

        String jobId = generate("o-zzzzzzzzzz/r-zzzz", null);

        XmlPath report = report(jobId);
        assertEquals("FAILED", report.getString(result("JobStatus")));
        assertEquals("InvalidEntityPath", report.getString(result("ErrorDetails.Code")));
        assertFalse(report.getString(result("ErrorDetails.Message")).isBlank());
    }

    @Test
    void anUnknownJobIdIsNoSuchEntity() {
        organizationWithScpsEnabled();

        iam(MANAGEMENT_ACCOUNT, "GetOrganizationsAccessReport")
                .formParam("JobId", "00000000-0000-0000-0000-000000000000")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void anOuPathIntersectsTheRootCeilingWithTheOneBelowIt() {
        // The only test that drives more than one level. The root leaves S3 and DynamoDB, the OU
        // leaves DynamoDB and IAM, so a report reading both answers DynamoDB alone: reading only
        // the OU would add IAM and reading only the root would add S3.
        String entityPath = organizationWithScpsEnabled();
        String rootId = entityPath.substring(entityPath.indexOf('/') + 1);
        String ouId = createOrganizationalUnit("Workloads", rootId);
        attach(createScp("RootCeiling", denyAllBut("s3", "dynamodb")), rootId);
        attach(createScp("OuCeiling", denyAllBut("dynamodb", "iam")), ouId);

        XmlPath report = report(generate(entityPath + "/" + ouId, null));

        assertEquals("1", report.getString(result("NumberOfServicesAccessible")));
        assertEquals("dynamodb", report.getString(result("AccessDetails.member.ServiceNamespace")));
    }

    @Test
    void theManagementAccountReportsEveryServiceAndIgnoresAPolicyId() {
        // Both halves of what the reference says about that entity: it "is not limited by SCPs",
        // so the root's ceiling does not apply to it, and a policy ID passed with it "is ignored".
        String entityPath = organizationWithScpsEnabled();
        attach(createScp("S3Ceiling", DENY_ALL_BUT_S3),
                entityPath.substring(entityPath.indexOf('/') + 1));
        String policyId = createScp("S3Only", ALLOW_S3_ONLY);

        XmlPath report = report(generate(entityPath + "/" + MANAGEMENT_ACCOUNT, policyId));

        assertTrue(Integer.parseInt(report.getString(result("NumberOfServicesAccessible"))) > 100,
                "the management account is not limited by SCPs, so every service is reachable");
    }

    @Test
    void anOrganizationWithoutServiceControlPoliciesFailsTheJob() {
        // The reference states SCPs must be enabled for the root and models no error for an
        // organization where they are not, so the failure lands where the other one does.
        String jobId = generate(organizationWithoutScpsEnabled(), null);

        XmlPath report = report(jobId);

        assertEquals("FAILED", report.getString(result("JobStatus")));
        assertEquals("PolicyTypeNotEnabled", report.getString(result("ErrorDetails.Code")));
    }

    @Test
    void anEntityPathWhoseOusAreNotParentAndChildFailsTheJob() {
        // Both OUs exist and the path is well formed, but Workloads is not under Platform. Taking
        // each segment as an independent policy target would report the Platform and Workloads
        // ceilings as a chain the organization does not have.
        String entityPath = organizationWithScpsEnabled();
        String rootId = entityPath.substring(entityPath.indexOf('/') + 1);
        String platform = createOrganizationalUnit("Platform", rootId);
        String workloads = createOrganizationalUnit("Workloads", rootId);

        XmlPath report = report(generate(entityPath + "/" + platform + "/" + workloads, null));

        assertEquals("FAILED", report.getString(result("JobStatus")));
        assertEquals("InvalidEntityPath", report.getString(result("ErrorDetails.Code")));
    }

    @Test
    void anEntityPathThatSkipsAnOuFailsTheJob() {
        // Workloads is under Platform, so naming it straight off the root skips a ceiling.
        String entityPath = organizationWithScpsEnabled();
        String rootId = entityPath.substring(entityPath.indexOf('/') + 1);
        String platform = createOrganizationalUnit("Platform", rootId);
        String workloads = createOrganizationalUnit("Workloads", platform);

        XmlPath report = report(generate(entityPath + "/" + workloads, null));

        assertEquals("FAILED", report.getString(result("JobStatus")));
        assertEquals("InvalidEntityPath", report.getString(result("ErrorDetails.Code")));
    }

    @Test
    void aManagementAccountBehindAnUnknownOuFailsTheJob() {
        // The management account reports every service, so a path resolved no further than its
        // last segment would answer a completed all-services report for a path that names an OU
        // the organization does not hold.
        String entityPath = organizationWithScpsEnabled();

        XmlPath report = report(generate(
                entityPath + "/ou-rge0-notthere/" + MANAGEMENT_ACCOUNT, null));

        assertEquals("FAILED", report.getString(result("JobStatus")));
        assertEquals("InvalidEntityPath", report.getString(result("ErrorDetails.Code")));
    }

    @Test
    void aPolicyIdNamingSomethingOtherThanAnScpFailsTheJob() {
        // Organizations keeps every policy type in one id space, and a tag policy carries no
        // statements, so evaluating one as a ceiling would read as a level that constrains
        // nothing and report every service there is.
        String entityPath = organizationWithScpsEnabled();
        String rootId = entityPath.substring(entityPath.indexOf('/') + 1);
        organizations(MANAGEMENT_ACCOUNT, "EnablePolicyType",
                "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"TAG_POLICY\"}")
                .when().post("/").then().statusCode(200);
        String tagPolicyId = organizations(MANAGEMENT_ACCOUNT, "CreatePolicy",
                "{\"Content\":\"{\\\"tags\\\":{}}\",\"Description\":\"Tags\","
                        + "\"Name\":\"Tags\",\"Type\":\"TAG_POLICY\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Policy.PolicySummary.Id");

        XmlPath report = report(generate(entityPath, tagPolicyId));

        assertEquals("FAILED", report.getString(result("JobStatus")));
        assertEquals("InvalidInputException", report.getString(result("ErrorDetails.Code")));
    }

    @Test
    void anEmptySortKeyIsRejectedRatherThanReadAsAnAbsentOne() {
        String jobId = generate(organizationWithScpsEnabled(), null);

        iam(MANAGEMENT_ACCOUNT, "GetOrganizationsAccessReport")
                .formParam("JobId", jobId)
                .formParam("SortKey", "")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void anEmptyPolicyIdIsRejectedRatherThanReadAsAnAbsentOne() {
        String entityPath = organizationWithScpsEnabled();

        iam(MANAGEMENT_ACCOUNT, "GenerateOrganizationsAccessReport")
                .formParam("EntityPath", entityPath)
                .formParam("OrganizationsPolicyId", "")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void anEntityPathNamingAnOuTheOrganizationDoesNotHoldFailsTheJob() {
        String entityPath = organizationWithScpsEnabled();

        XmlPath report = report(generate(entityPath + "/ou-rge0-notthere", null));

        assertEquals("FAILED", report.getString(result("JobStatus")));
        assertEquals("OrganizationalUnitNotFoundException",
                report.getString(result("ErrorDetails.Code")));
    }

    /**
     * The ordering DRIFT-3 was about, on this reader. {@code jobIDType} is a fixed 36 characters,
     * so the shape is refused before anything is resolved: no organization is built here, and none
     * has to be, which is the point.
     */
    @Test
    void aJobIdOutsideItsDeclaredLengthIsRefusedWithoutAnOrganization() {
        iam(MANAGEMENT_ACCOUNT, "GetOrganizationsAccessReport").formParam("JobId", "nope")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo(
                    "1 validation error detected: Value at 'jobId' failed to satisfy constraint: "
                            + "Member must have length equal to 36"));
    }

    /**
     * A well-formed id no job carries, with a {@code MaxItems} the model refuses: the paging
     * members are read before the job lookup, so this is a 400 rather than the 404 the missing job
     * would answer first.
     */
    @Test
    void anUnknownJobWithAMalformedMaxItemsIsAValidationErrorRatherThanNotFound() {
        iam(MANAGEMENT_ACCOUNT, "GetOrganizationsAccessReport")
            .formParam("JobId", "00000000-0000-4000-8000-000000000000")
            .formParam("MaxItems", "1001")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo(
                    "1 validation error detected: Value '1001' at 'maxItems' failed to satisfy "
                            + "constraint: Member must have value less than or equal to 1000"));
    }
}
