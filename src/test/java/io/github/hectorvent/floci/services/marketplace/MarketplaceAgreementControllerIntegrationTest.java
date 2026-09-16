package io.github.hectorvent.floci.services.marketplace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MarketplaceAgreementControllerIntegrationTest {
    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createsAcceptsDescribesAndSearchesAgreement() {
        String requestId = rpc("CreateAgreementRequest",
                "{\"intent\":\"NEW\",\"agreementProposalIdentifier\":\"ap-Example123\","
                        + "\"requestedTerms\":[{\"id\":\"term-1\"}]}")
                .statusCode(200)
                .body("agreementRequestId", notNullValue())
                .extract().path("agreementRequestId");
        String agreementId = rpc("AcceptAgreementRequest",
                "{\"agreementRequestId\":\"" + requestId + "\"}")
                .statusCode(200)
                .body("agreementId", notNullValue())
                .extract().path("agreementId");

        rpc("DescribeAgreement", "{\"agreementId\":\"" + agreementId + "\"}")
                .statusCode(200)
                .body("agreementId", equalTo(agreementId))
                .body("status", equalTo("ACTIVE"));
        rpc("GetAgreementTerms", "{\"agreementId\":\"" + agreementId + "\"}")
                .statusCode(200)
                .body("acceptedTerms[0].id", equalTo("term-1"));
        rpc("SearchAgreements", "{\"filters\":[{\"name\":\"AgreementType\",\"values\":[\"PurchaseAgreement\"]},{\"name\":\"Status\",\"values\":[\"ACTIVE\"]}]}")
                .statusCode(200)
                .body("agreementViewSummaries[0].agreementId", equalTo(agreementId));
        rpc("SearchAgreements",
                "{\"filters\":[{\"name\":\"AgreementType\",\"values\":[\"PurchaseAgreement\"]},{\"name\":\"ResourceIdentifier\",\"values\":[\"does-not-exist\"]}]}")
                .statusCode(200)
                .body("agreementViewSummaries.size()", equalTo(0));
    }

    @Test
    void cancellationRequestTransitionsAgreement() {
        String requestId = rpc("CreateAgreementRequest",
                "{\"intent\":\"NEW\",\"agreementProposalIdentifier\":\"ap-Cancel123\","
                        + "\"requestedTerms\":[{\"id\":\"term-2\"}]}")
                .statusCode(200)
                .extract().path("agreementRequestId");
        String agreementId = rpc("AcceptAgreementRequest",
                "{\"agreementRequestId\":\"" + requestId + "\"}")
                .statusCode(200)
                .extract().path("agreementId");
        String cancellationId = rpc("SendAgreementCancellationRequest",
                "{\"agreementId\":\"" + agreementId + "\",\"reasonCode\":\"OTHER\"}")
                .statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"))
                .extract().path("agreementCancellationRequestId");

        rpc("AcceptAgreementCancellationRequest",
                "{\"agreementId\":\"" + agreementId + "\",\"agreementCancellationRequestId\":\""
                        + cancellationId + "\"}")
                .statusCode(200)
                .body("status", equalTo("APPROVED"));
        rpc("DescribeAgreement", "{\"agreementId\":\"" + agreementId + "\"}")
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));
    }

    @Test
    void unsupportedRegionIsRejected() {
        given()
                .contentType("application/x-amz-json-1.0")
                .header("Authorization", auth("us-west-2"))
                .header("X-Amz-Target", "AWSMPCommerceService_v20200301.SearchAgreements")
                .body("{}")
                .post("/")
                .then()
                .statusCode(400);
    }

    private static io.restassured.response.ValidatableResponse rpc(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.0")
                .header("Authorization", auth())
                .header("X-Amz-Target", "AWSMPCommerceService_v20200301." + action)
                .body(body)
                .post("/")
                .then();
    }

    private static String auth() {
        return auth("us-east-1");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/" + region
                + "/aws-marketplace/aws4_request";
    }
}
