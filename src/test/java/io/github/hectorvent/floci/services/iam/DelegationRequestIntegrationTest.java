package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hectorvent.floci.testing.DelegationRequests.entityArn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private static final String SUMMARY_RESULT =
            "GetHumanReadableSummaryResponse.GetHumanReadableSummaryResult.";
    private static final String PARTNER_STS_AUTH = "AWS4-HMAC-SHA256 Credential=" + PARTNER
            + "/20261011/us-east-1/sts/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String CUSTOMER_STS_AUTH = "AWS4-HMAC-SHA256 Credential=" + CUSTOMER
            + "/20261011/us-east-1/sts/aws4_request, SignedHeaders=host, Signature=abc";

    /**
     * The exchange token is answered by no operation: it reaches the partner on the SNS topic the
     * request names. This class drives no topic, so it reads the token off the record, and
     * {@code DelegationNotificationIntegrationTest} is where the notification carrying it is
     * driven over the wire instead.
     */
    @Inject
    DelegationRequestService delegationRequests;

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

    private String finalizedId() {
        String id = createdId();
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);
        byId(CUSTOMER_AUTH, "AcceptDelegationRequest", id).statusCode(200);
        byId(CUSTOMER_AUTH, "SendDelegationToken", id).statusCode(200);
        return id;
    }

    private String tokenOf(String id) {
        return RequestScopes.callAs(CUSTOMER,
                () -> delegationRequests.get(CUSTOMER, id).getExchangeToken());
    }

    private static ValidatableResponse exchange(String authorization, String token) {
        return given().header("Authorization", authorization)
                .formParam("Action", "GetDelegatedAccessToken")
                .formParam("Version", "2011-06-15")
                .formParam("TradeInToken", token)
            .when().post("/").then();
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

    @Test
    void theCustomerReleasesTheTokenAndTheRequestFinalizes() {
        String id = createdId();
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);
        byId(CUSTOMER_AUTH, "AcceptDelegationRequest", id).statusCode(200);
        assertEquals("ACCEPTED", stateOf(CUSTOMER_AUTH, id));

        byId(CUSTOMER_AUTH, "SendDelegationToken", id).statusCode(200)
                .body("SendDelegationTokenResponse.ResponseMetadata.RequestId", not(equalTo(null)));

        assertEquals("FINALIZED", stateOf(CUSTOMER_AUTH, id));
        assertNotNull(tokenOf(id));
    }

    @Test
    void aRequestThatWasNeverAcceptedReleasesNothing() {
        String id = createdId();
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);

        byId(CUSTOMER_AUTH, "SendDelegationToken", id).statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("ConcurrentModification"));
        assertEquals("ASSIGNED", stateOf(CUSTOMER_AUTH, id));
    }

    @Test
    void thePartnerExchangesTheTokenForCredentialsInTheCustomersAccount() {
        String id = finalizedId();

        ValidatableResponse exchanged = exchange(PARTNER_STS_AUTH, tokenOf(id)).statusCode(200);

        String prefix = "GetDelegatedAccessTokenResponse.GetDelegatedAccessTokenResult.";
        exchanged.body(prefix + "Credentials.AccessKeyId", startsWith("ASIA"))
                .body(prefix + "Credentials.SessionToken", not(equalTo(null)))
                .body(prefix + "AssumedPrincipal", equalTo("arn:aws:iam::" + CUSTOMER + ":root"));
        assertFalse(exchanged.extract().asString().contains("PackedPolicySize"),
                "the model deprecates it and states it is not populated for this operation");
    }

    /**
     * The delegation is the customer's grant, so the session it mints answers as the customer's
     * principal however the partner signed the exchange.
     *
     * <p>The account credential approved this request, so the principal is the account root and
     * the session answers as the account itself. The account alone would not pin much: it comes
     * off the principal ARN rather than the namespace the session was filed in, so it would pass
     * with the session stored under the partner too, and that half is pinned in
     * {@code StsQueryHandlerTest} instead.
     */
    @Test
    void theMintedCredentialsActInTheCustomersAccount() {
        String prefix = "GetDelegatedAccessTokenResponse.GetDelegatedAccessTokenResult.";
        ValidatableResponse exchanged =
                exchange(PARTNER_STS_AUTH, tokenOf(finalizedId())).statusCode(200);
        String accessKeyId = exchanged.extract().path(prefix + "Credentials.AccessKeyId");
        String sessionToken = exchanged.extract().path(prefix + "Credentials.SessionToken");

        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20261011/us-east-1/sts/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Security-Token", sessionToken)
                .formParam("Action", "GetCallerIdentity")
                .formParam("Version", "2011-06-15")
            .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account",
                        equalTo(CUSTOMER))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:iam::" + CUSTOMER + ":root"));
    }

    @Test
    void theTokenIsOnlyGoodInTheHandsOfTheAccountThatFiledTheRequest() {
        String id = finalizedId();

        exchange(CUSTOMER_STS_AUTH, tokenOf(id)).statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    @Test
    void aTokenNobodyIssuedIsRefusedTheWayAnExpiredOneIs() {
        exchange(PARTNER_STS_AUTH, "not-a-token").statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ExpiredTradeInTokenException"));
    }

    @Test
    void theExchangeNeedsAToken() {
        given().header("Authorization", PARTNER_STS_AUTH)
                .formParam("Action", "GetDelegatedAccessToken")
                .formParam("Version", "2011-06-15")
            .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    private static ValidatableResponse summarize(String authorization, String entityArn,
                                                 String locale) {
        RequestSpecification request = iam(authorization)
                .formParam("Action", "GetHumanReadableSummary")
                .formParam("Version", "2010-05-08")
                .formParam("EntityArn", entityArn);
        if (locale != null) {
            request = request.formParam("Locale", locale);
        }
        return request.when().post("/").then();
    }

    @Test
    void theSummaryComesBackAsMarkdownInTheLocaleItWasAskedFor() {
        String id = createdId();

        ValidatableResponse summary = summarize(PARTNER_AUTH, entityArn(PARTNER, id), "en")
                .statusCode(200)
                .body(SUMMARY_RESULT + "SummaryState", equalTo("AVAILABLE"))
                .body(SUMMARY_RESULT + "Locale", equalTo("en"));

        String content = summary.extract().path(SUMMARY_RESULT + "SummaryContent");
        assertTrue(content.startsWith("# Delegation request " + id), content);
        assertTrue(content.contains("Set up the integration"), content);
        assertTrue(content.contains("Policy template `" + TEMPLATE + "`"), content);
        assertTrue(content.contains("State `UNASSIGNED`"), content);
    }

    @Test
    void anIndexedParameterValueThatCarriesNoValueIsNotSummarized() {
        // The indices come off the keys the request carries, so a dotted sub-key makes an index
        // exist with no value under it. Nothing is stored for that index and the summary says
        // nothing about it either, rather than reading it back as a value.
        String id = iam(PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Version", "2010-05-08")
                .formParam("Description", "Set up the integration")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", workflowId())
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
                .formParam("Permissions.Parameters.member.1.Name", "BucketName")
                .formParam("Permissions.Parameters.member.1.Type", "string")
                .formParam("Permissions.Parameters.member.1.Values.member.1.Nested", "ignored")
            .when().post("/").then().statusCode(200)
                .extract().path("CreateDelegationRequestResponse"
                        + ".CreateDelegationRequestResult.DelegationRequestId");

        String content = summarize(PARTNER_AUTH, entityArn(PARTNER, id), "en").statusCode(200)
                .extract().path(SUMMARY_RESULT + "SummaryContent");

        assertTrue(content.contains("- Parameter `BucketName` (string)"), content);
        assertFalse(content.contains("null"), content);
    }

    @Test
    void anEntityOfAnotherTypeIsAnsweredWithoutASummary() {
        // The member is left out of the answer altogether: content is non-empty only if the state
        // is AVAILABLE, and there is no summary of a user to give.
        summarize(PARTNER_AUTH, "arn:aws:iam::" + PARTNER + ":user/admin", null).statusCode(200)
                .body(SUMMARY_RESULT + "SummaryState", equalTo("NOT_SUPPORTED"))
                .body(SUMMARY_RESULT + "Locale", equalTo("en"))
                .body(not(containsString("<SummaryContent>")));
    }

    @Test
    void aLocaleFlociDoesNotWriteIsAnsweredWithoutASummaryToo() {
        String id = createdId();

        summarize(PARTNER_AUTH, entityArn(PARTNER, id), "ja").statusCode(200)
                .body(SUMMARY_RESULT + "SummaryState", equalTo("NOT_AVAILABLE"))
                .body(SUMMARY_RESULT + "Locale", equalTo("ja"))
                .body(not(containsString("<SummaryContent>")));
    }

    @Test
    void theSummaryIsAsVisibleAsTheRequestItSummarizes() {
        String id = createdId();
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);

        // Claimed by the customer, so the partner that filed it can no longer read either the
        // request or its summary, which is the rule GetDelegationRequest publishes.
        summarize(PARTNER_AUTH, entityArn(PARTNER, id), null).statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
        summarize(CUSTOMER_AUTH, entityArn(PARTNER, id), null).statusCode(200)
                .body(SUMMARY_RESULT + "SummaryState", equalTo("AVAILABLE"));
    }

    @Test
    void anEntityArnNoRequestMatchesIsNoSuchEntityAndOneThatIsNoArnIsInvalidInput() {
        summarize(PARTNER_AUTH, entityArn(PARTNER, "dr-0000000000000000"), null).statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
        summarize(PARTNER_AUTH, "delegation-request/dr-0123456789abcdef", null).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
        summarize(PARTNER_AUTH, "arn:aws:iam::1:u", null).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("entityArn"));
    }

    @Test
    void theReleaseRefusesAnIdThatBreaksItsPatternAndNotOnlyAnAbsentOne() {
        // The other half of the check above, and the one a regression could lose quietly: an id
        // that arrived and cannot be a request id at all, rather than one that never arrived.
        iam(CUSTOMER_AUTH)
                .formParam("Action", "SendDelegationToken")
                .formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", "dr-0123456789abcdef!")
            .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("delegationRequestId"));
    }

    @Test
    void theRootApprovalCapHoldsOverTheWireToo() {
        // The customer signs with its account id here, which names no IAM user, so this suite's
        // approver is the account root: a request longer than four hours cannot be approved with
        // it, which is the guide's rule rather than a local one.
        String id = create(workflowId(), null, "18000").statusCode(200)
                .extract().path("CreateDelegationRequestResponse"
                        + ".CreateDelegationRequestResult.DelegationRequestId");
        byId(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);

        byId(CUSTOMER_AUTH, "AcceptDelegationRequest", id).statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"))
                .body("ErrorResponse.Error.Message", containsString("4 hours or less"));
    }

    @Test
    void theReleaseNeedsARequestIdLikeEveryOtherCallOnOne() {
        // delegationRequestIdType declares a length and a pattern, so an omitted id breaks a
        // constraint the model publishes and answers as the by-id operations already do.
        iam(CUSTOMER_AUTH)
                .formParam("Action", "SendDelegationToken")
                .formParam("Version", "2010-05-08")
            .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("delegationRequestId"));
    }
}
