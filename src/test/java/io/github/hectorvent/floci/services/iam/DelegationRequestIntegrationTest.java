package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delegation request lifecycle over the wire, across the two accounts it spans.
 *
 * <p>The partner files the request and the customer claims and answers it, so each call is signed
 * with the account that would make it: the access key id is the account id, which is how the rest
 * of the suite switches accounts.
 */
@QuarkusTest
class DelegationRequestIntegrationTest {

    private static final String PARTNER = "000000000001";
    private static final String CUSTOMER = "000000000002";
    private static final String PARTNER_AUTH = "AWS4-HMAC-SHA256 Credential=" + PARTNER
            + "/20261011/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String CUSTOMER_AUTH = "AWS4-HMAC-SHA256 Credential=" + CUSTOMER
            + "/20261011/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";
    private static final String TEMPLATE = "arn:aws:iam::partner:policy/template/example";

    private static String workflowId() {
        return "workflow-" + Long.toString(System.nanoTime(), 36);
    }

    private static RequestSpecification iam(String authorization) {
        return given().header("Authorization", authorization);
    }

    private static ValidatableResponse create(String workflowId, String ownerAccountId,
                                              String sessionDuration) {
        RequestSpecification request = iam(PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Version", "2010-05-08")
                .formParam("Description", "Set up the integration")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", workflowId)
                .formParam("SessionDuration", sessionDuration)
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
                .formParam("Permissions.Parameters.member.1.Name", "BucketName")
                .formParam("Permissions.Parameters.member.1.Type", "string")
                .formParam("Permissions.Parameters.member.1.Values.member.1", "example-bucket");
        if (ownerAccountId != null) {
            request = request.formParam("OwnerAccountId", ownerAccountId);
        }
        return request.when().post("/").then();
    }

    private static String createdId() {
        return create(workflowId(), null, "3600").statusCode(200)
                .extract().path("CreateDelegationRequestResponse.CreateDelegationRequestResult"
                        + ".DelegationRequestId");
    }

    private static ValidatableResponse byId(String authorization, String action, String id) {
        return iam(authorization)
                .formParam("Action", action)
                .formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id)
            .when().post("/").then();
    }

    private static ValidatableResponse get(String authorization, String id) {
        return byId(authorization, "GetDelegationRequest", id).statusCode(200);
    }

    private static String stateOf(String authorization, String id) {
        return get(authorization, id).extract()
                .path("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest.State");
    }

    @Test
    void createAnswersAnIdAndADeepLinkAndStartsUnassigned() {
        ValidatableResponse created = create(workflowId(), null, "3600").statusCode(200);

        String id = created.extract().path("CreateDelegationRequestResponse"
                + ".CreateDelegationRequestResult.DelegationRequestId");
        created.body("CreateDelegationRequestResponse.CreateDelegationRequestResult"
                + ".ConsoleDeepLink", containsString(id));
        assertTrue(id.startsWith("dr-"), "the reference's own examples prefix the id with dr-");
        assertEquals("UNASSIGNED", stateOf(PARTNER_AUTH, id));
    }

    @Test
    void theRequestCarriesBackWhatItWasFiledWith() {
        String id = createdId();

        get(PARTNER_AUTH, id)
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".Description", equalTo("Set up the integration"))
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".SessionDuration", equalTo("3600"))
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".RequestorId", equalTo(PARTNER))
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".Permissions.PolicyTemplateArn", equalTo(TEMPLATE))
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".Permissions.Parameters.member.Name", equalTo("BucketName"))
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".Permissions.Parameters.member.Values.member", equalTo("example-bucket"));
    }

    @Test
    void theCustomerClaimsApprovesAndIsRecordedAsOwnerAndApprover() {
        String id = createdId();

        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);
        assertEquals("ASSIGNED", stateOf(CUSTOMER_AUTH, id));
        get(CUSTOMER_AUTH, id).body("GetDelegationRequestResponse.GetDelegationRequestResult"
                + ".DelegationRequest.OwnerAccountId", equalTo(CUSTOMER));

        byId(CUSTOMER_AUTH, "AcceptDelegationRequest", id).statusCode(200);
        assertEquals("ACCEPTED", stateOf(CUSTOMER_AUTH, id));
        get(CUSTOMER_AUTH, id).body("GetDelegationRequestResponse.GetDelegationRequestResult"
                + ".DelegationRequest.ApproverId", startsWith("arn:aws:iam::" + CUSTOMER + ":"));
    }

    @Test
    void anUpdateForwardsForApprovalAndKeepsItsNotes() {
        String id = createdId();
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);

        iam(CUSTOMER_AUTH)
                .formParam("Action", "UpdateDelegationRequest")
                .formParam("DelegationRequestId", id)
                .formParam("Notes", "please review")
            .when().post("/").then().statusCode(200);

        assertEquals("PENDING_APPROVAL", stateOf(CUSTOMER_AUTH, id));
        get(CUSTOMER_AUTH, id).body("GetDelegationRequestResponse.GetDelegationRequestResult"
                + ".DelegationRequest.Notes", equalTo("please review"));
    }

    @Test
    void rejectionKeepsItsReasonAndClosesTheRequest() {
        String id = createdId();
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);

        iam(CUSTOMER_AUTH)
                .formParam("Action", "RejectDelegationRequest")
                .formParam("DelegationRequestId", id)
                .formParam("Notes", "not this quarter")
            .when().post("/").then().statusCode(200);

        assertEquals("REJECTED", stateOf(CUSTOMER_AUTH, id));
        get(CUSTOMER_AUTH, id).body("GetDelegationRequestResponse.GetDelegationRequestResult"
                + ".DelegationRequest.RejectionReason", equalTo("not this quarter"));
        byId(CUSTOMER_AUTH, "AcceptDelegationRequest", id)
            .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("ConcurrentModification"));
    }

    @Test
    void aRequestAimedAtOneAccountIsNotFoundFromAnother() {
        String id = create(workflowId(), CUSTOMER, "3600").statusCode(200)
                .extract().path("CreateDelegationRequestResponse"
                        + ".CreateDelegationRequestResult.DelegationRequestId");

        // The partner filed it, and once it names a target account only that account sees it.
        byId(PARTNER_AUTH, "GetDelegationRequest", id)
            .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
        assertEquals("UNASSIGNED", stateOf(CUSTOMER_AUTH, id));
    }

    @Test
    void listingShowsTheClaimedRequestAndSpellsIsTruncatedTheWayTheModelDoes() {
        String claimed = createdId();
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", claimed).statusCode(200);
        String unclaimed = createdId();

        ValidatableResponse listed = iam(CUSTOMER_AUTH)
                .formParam("Action", "ListDelegationRequests")
                .formParam("Version", "2010-05-08")
            .when().post("/").then().statusCode(200);

        List<String> ids = listed.extract().xmlPath().getList(
                "ListDelegationRequestsResponse.ListDelegationRequestsResult.DelegationRequests"
                        + ".member.DelegationRequestId");
        assertTrue(ids.contains(claimed), "the claimed request is the customer's to list");
        assertTrue(!ids.contains(unclaimed),
                "an unowned request is left out even when any account could read it by id");
        // Lower-cased on this one operation, which is how the model names the member.
        listed.body("ListDelegationRequestsResponse.ListDelegationRequestsResult.isTruncated",
                equalTo("false"));
    }

    @Test
    void thePermissionCheckIsOnlyAnsweredWhenItIsAskedFor() {
        String id = createdId();

        // Asserted on the body rather than the path: an absent element reads back as an empty
        // string through XmlPath, which would pass against a present but empty one.
        get(PARTNER_AUTH, id).body(not(containsString("PermissionCheckStatus")));

        iam(PARTNER_AUTH)
                .formParam("Action", "GetDelegationRequest")
                .formParam("DelegationRequestId", id)
                .formParam("DelegationPermissionCheck", "true")
            .when().post("/").then().statusCode(200)
                .body("GetDelegationRequestResponse.GetDelegationRequestResult"
                        + ".PermissionCheckStatus", equalTo("COMPLETE"))
                // A delegation request carries no permission for Floci to evaluate, and the enum
                // models UNSURE for exactly that.
                .body("GetDelegationRequestResponse.GetDelegationRequestResult"
                        + ".PermissionCheckResult", equalTo("UNSURE"));
    }

    @Test
    void anUnknownRequestIsNoSuchEntity() {
        byId(PARTNER_AUTH, "GetDelegationRequest", "dr-000000000000000000000000")
            .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void aMalformedIdIsAValidationErrorAsAnywhereElseInIam() {
        // ValidationError is a common error in the reference, published for every IAM operation
        // and listed on none of them, so a member that breaks its own pattern is refused with it
        // here too rather than with the InvalidInput these operations happen to model.
        byId(PARTNER_AUTH, "GetDelegationRequest", "dr-short")
            .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void aReusedWorkflowIdIsRefused() {
        String workflowId = workflowId();
        create(workflowId, null, "3600").statusCode(200);

        create(workflowId, null, "3600")
            .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    @Test
    void anEmptyDescriptionIsAcceptedAndAnAbsentOneIsNot() {
        // Over the wire is where the two differ: Description= arrives as an empty value and a
        // missing Description does not arrive at all. The member is required and its pattern ends
        // in '*' with no minimum, so only the second is an omission.
        iam(PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Description", "")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", workflowId())
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
            .when().post("/").then().statusCode(200);

        iam(PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", workflowId())
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void sessionDurationOutsideTheModelsRangeIsRefused() {
        create(workflowId(), null, "60")
            .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void aBooleanMemberHasToCarryABoolean() {
        // Boolean.valueOf would read anything at all as false, which would store a value the
        // caller never sent. The Query protocol writes true or false, so nothing else is a value
        // the member can carry, and it is refused the way a malformed number is.
        iam(PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Description", "d")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", workflowId())
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
                .formParam("OnlySendByOwner", "yes")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message", containsString("Member must be a boolean"));

        iam(PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Description", "d")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", workflowId())
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
                .formParam("OnlySendByOwner", "TRUE")
            .when().post("/").then().statusCode(200);
    }

    @Test
    void aPolicyParameterListNumberedFromTwoKeepsItsEntries() {
        // An indexed list read by counting up from one stops at the first index that is missing,
        // which would accept the request and drop what it carried.
        String id = iam(PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Description", "d")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", workflowId())
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
                .formParam("Permissions.Parameters.member.2.Name", "BucketName")
                .formParam("Permissions.Parameters.member.2.Type", "string")
                .formParam("Permissions.Parameters.member.2.Values.member.3", "reports")
            .when().post("/").then().statusCode(200)
                .extract().path("CreateDelegationRequestResponse.CreateDelegationRequestResult"
                        + ".DelegationRequestId");

        byId(PARTNER_AUTH, "GetDelegationRequest", id)
            .statusCode(200)
                .body("GetDelegationRequestResponse.GetDelegationRequestResult"
                        + ".DelegationRequest.Permissions.Parameters.member.Name",
                        equalTo("BucketName"))
                .body("GetDelegationRequestResponse.GetDelegationRequestResult"
                        + ".DelegationRequest.Permissions.Parameters.member.Values.member",
                        equalTo("reports"));
    }
}
