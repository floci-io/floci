package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A service namespace a request names has to be one AWS publishes. The member's pattern admits
 * anything word-shaped, so a value like {@code amazon-s3} satisfies the model and names no service,
 * and AWS refuses it rather than reporting that the identity reaches nothing. The two operations
 * that take one word the refusal differently, and both sentences are recorded.
 *
 * <p>{@code docs/services/iam.md} carries the recordings.
 */
@QuarkusTest
class IamServiceNamespaceCheckIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";
    private static final String TYPE = "ErrorResponse.Error.Type";

    /** Word-shaped, so the declared pattern admits it, and no service of AWS's. */
    private static final String NOT_A_SERVICE = "amazon-s3";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private String createUser() {
        String userName = unique("namespace-user");
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200));
        return "arn:aws:iam::000000000000:user/" + userName;
    }

    private String jobFor(String arn) {
        return iam("GenerateServiceLastAccessedDetails").formParam("Arn", arn)
                .when().post("/").then().statusCode(200)
                .extract().path("GenerateServiceLastAccessedDetailsResponse."
                        + "GenerateServiceLastAccessedDetailsResult.JobId");
    }

    /** The list operation names the rejected value inside AWS's own brackets. */
    @Test
    void aNamespaceThatNamesNoServiceIsRefusedByTheListOperation() {
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", createUser())
                .formParam("ServiceNamespaces.member.1", NOT_A_SERVICE)
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(TYPE, equalTo("Sender"))
                .body(MESSAGE, equalTo("Invalid service namespace: [" + NOT_A_SERVICE + "]"));
    }

    /** Several bad values go inside the one pair of brackets, which is the reading. */
    @Test
    void severalRejectedNamespacesShareTheBrackets() {
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", createUser())
                .formParam("ServiceNamespaces.member.1", NOT_A_SERVICE)
                .formParam("ServiceNamespaces.member.2", "s3")
                .formParam("ServiceNamespaces.member.3", "not-a-service")
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, equalTo("Invalid service namespace: ["
                        + NOT_A_SERVICE + ", not-a-service]"));
    }

    /** The single-namespace reader words it differently, AWS's own misspelling included. */
    @Test
    void aNamespaceThatNamesNoServiceIsRefusedByTheEntityReader() {
        String arn = createUser();
        iam("GetServiceLastAccessedDetailsWithEntities").formParam("JobId", jobFor(arn))
                .formParam("ServiceNamespace", NOT_A_SERVICE)
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, equalTo("Service namepace " + NOT_A_SERVICE
                        + " is either invalid or unsupported."));
    }

    /**
     * The job is read ahead of the namespace, because the job is part of what decides whether a
     * namespace can be read: one the report lists is readable whatever the catalog says. So a job
     * that does not exist is answered as a missing job, and the namespace is judged against a
     * report that exists. Which of the two a request breaking both is answered is recorded
     * nowhere, and this is the order that lets the two readers agree.
     */
    @Test
    void aJobThatDoesNotExistIsAnsweredBeforeTheNamespace() {
        iam("GetServiceLastAccessedDetailsWithEntities")
                .formParam("JobId", "00000000-0000-4000-8000-000000000000")
                .formParam("ServiceNamespace", NOT_A_SERVICE)
                .when().post("/").then().statusCode(404)
                .body(CODE, equalTo("NoSuchEntity"));
    }

    /**
     * Where the vendored shape carries the member, a value breaking its published pattern still
     * answers the constraint, which runs ahead of this check.
     */
    @Test
    void aValueBreakingTheDeclaredPatternStillAnswersTheConstraint() {
        iam("GetServiceLastAccessedDetailsWithEntities")
                .formParam("JobId", "00000000-0000-4000-8000-000000000000")
                .formParam("ServiceNamespace", "not a namespace")
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"));
    }

    /**
     * A value breaking the published pattern is answered that pattern, not the catalog. The list
     * member is vendored now, so the declared constraints are read ahead of the catalog check and
     * only a well-formed value reaches it.
     */
    @Test
    void aMalformedListMemberIsAnsweredThePublishedPattern() {
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", createUser())
                .formParam("ServiceNamespaces.member.1", "not a namespace")
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(MESSAGE, equalTo("1 validation error detected: Value at"
                        + " 'serviceNamespaces.1.member' failed to satisfy constraint: Member must"
                        + " satisfy regular expression pattern: [\\w-]*"));
    }

    /**
     * An absent list is how an empty one arrives on this protocol, and the model requires the
     * member, so that is what answers it. This package used to answer `InvalidInput` and a
     * sentence of its own here, which no recording carries.
     */
    @Test
    void anAbsentListIsTheRequiredMemberTheModelDeclares() {
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", createUser())
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(MESSAGE, equalTo("1 validation error detected: Value at"
                        + " 'serviceNamespaces' failed to satisfy constraint: Member must not be"
                        + " null"));
    }

    /**
     * The catalog is vendored, so a policy may grant a service published after it. The report
     * keeps the namespace that policy names, and a request for its entities is answered rather
     * than refused: the job is what decides, so this emulator cannot report a service and then
     * refuse to read it.
     */
    @Test
    void aNamespaceTheJobItselfReportsIsReadable() {
        String userName = unique("namespace-future");
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200));
        iam("PutUserPolicy").formParam("UserName", userName)
                .formParam("PolicyName", "grants-a-later-service")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":"
                        + "[{\"Effect\":\"Allow\",\"Action\":\"somefutureservice:DoThing\","
                        + "\"Resource\":\"*\"}]}")
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPolicy").formParam("UserName", userName)
                .formParam("PolicyName", "grants-a-later-service")
                .when().post("/").then().statusCode(200));

        String jobId = jobFor("arn:aws:iam::000000000000:user/" + userName);
        iam("GetServiceLastAccessedDetailsWithEntities").formParam("JobId", jobId)
                .formParam("ServiceNamespace", "somefutureservice")
                .when().post("/").then().statusCode(200)
                .body(containsString("EntityDetailsList"));

        // Nothing else is exempt: a namespace neither the job nor the catalog knows is refused.
        iam("GetServiceLastAccessedDetailsWithEntities").formParam("JobId", jobId)
                .formParam("ServiceNamespace", NOT_A_SERVICE)
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"));
    }

    /** The control: published namespaces are answered as before, on both operations. */
    @Test
    void publishedNamespacesAreUntouched() {
        String arn = createUser();
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
                .formParam("ServiceNamespaces.member.1", "s3")
                .formParam("ServiceNamespaces.member.2", "dynamodb")
                .when().post("/").then().statusCode(200);
        iam("GetServiceLastAccessedDetailsWithEntities").formParam("JobId", jobFor(arn))
                .formParam("ServiceNamespace", "s3")
                .when().post("/").then().statusCode(200);
    }
}
