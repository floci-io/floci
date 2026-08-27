package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Wire-level tests for {@code AssociatePrincipalWithPortfolio},
 * {@code DisassociatePrincipalFromPortfolio}, {@code ListPrincipalsForPortfolio},
 * {@code ListPortfolioAccess} and {@code ListOrganizationPortfolioAccess}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001).
 */
@QuarkusTest
class ServiceCatalogPrincipalAccessConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWS242ServiceCatalogService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    private static String createPortfolio(String displayName) {
        return call("CreatePortfolio", "{\"DisplayName\":\"" + displayName
                + "\",\"ProviderName\":\"floci-test\"}")
                .then().statusCode(200)
                .extract().path("PortfolioDetail.Id");
    }

    // ---------- AssociatePrincipalWithPortfolio ----------

    @Test
    void associatePrincipalWithPortfolio_rejectsUnknownPrincipalType() {
        String portfolioId = createPortfolio("ab-principal-badtype");
        call("AssociatePrincipalWithPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"PrincipalARN\":\"arn:aws:iam::000000000000:role/my-role\","
                + "\"PrincipalType\":\"FEDERATED\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    @Test
    void associatePrincipalWithPortfolio_returnsEmptyBody() {
        String portfolioId = createPortfolio("ab-principal-associate");
        call("AssociatePrincipalWithPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"PrincipalARN\":\"arn:aws:iam::000000000000:role/my-role\","
                + "\"PrincipalType\":\"IAM\"}")
        .then()
            .statusCode(200);
    }

    @Test
    void associatePrincipalWithPortfolio_unknownPortfolio_returnsResourceNotFound() {
        call("AssociatePrincipalWithPortfolio", "{\"PortfolioId\":\"port-doesnotexist\","
                + "\"PrincipalARN\":\"arn:aws:iam::000000000000:role/my-role\","
                + "\"PrincipalType\":\"IAM\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ListPrincipalsForPortfolio ----------

    @Test
    void listPrincipalsForPortfolio_returnsAssociatedPrincipal() {
        String portfolioId = createPortfolio("ab-principal-list");
        call("AssociatePrincipalWithPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"PrincipalARN\":\"arn:aws:iam::000000000000:role/listed-role\","
                + "\"PrincipalType\":\"IAM\"}")
        .then().statusCode(200);

        call("ListPrincipalsForPortfolio", "{\"PortfolioId\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(200)
            .body("Principals.PrincipalARN", hasItem("arn:aws:iam::000000000000:role/listed-role"))
            .body("Principals.PrincipalType", hasItem("IAM"));
    }

    // ---------- DisassociatePrincipalFromPortfolio ----------

    @Test
    void disassociatePrincipalFromPortfolio_removesPrincipal() {
        String portfolioId = createPortfolio("ab-principal-disassociate");
        String arn = "arn:aws:iam::000000000000:role/removed-role";
        call("AssociatePrincipalWithPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"PrincipalARN\":\"" + arn + "\",\"PrincipalType\":\"IAM\"}")
        .then().statusCode(200);

        call("DisassociatePrincipalFromPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"PrincipalARN\":\"" + arn + "\"}")
        .then()
            .statusCode(200);

        call("ListPrincipalsForPortfolio", "{\"PortfolioId\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(200)
            .body("Principals.PrincipalARN", org.hamcrest.Matchers.not(hasItem(arn)));
    }

    // ---------- ListPortfolioAccess ----------

    @Test
    void listPortfolioAccess_returnsAccountsSharedWithPortfolio() {
        String portfolioId = createPortfolio("ab-portfolio-access");
        call("CreatePortfolioShare", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"AccountId\":\"333333333333\"}")
        .then().statusCode(200);

        call("ListPortfolioAccess", "{\"PortfolioId\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(200)
            .body("AccountIds", hasItem("333333333333"));
    }

    @Test
    void listPortfolioAccess_unknownPortfolio_returnsResourceNotFound() {
        call("ListPortfolioAccess", "{\"PortfolioId\":\"port-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ListOrganizationPortfolioAccess ----------

    @Test
    void listOrganizationPortfolioAccess_noShares_returnsEmptyList() {
        String portfolioId = createPortfolio("ab-org-access-empty");
        call("ListOrganizationPortfolioAccess", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"OrganizationNodeType\":\"ORGANIZATION\"}")
        .then()
            .statusCode(200)
            .body("OrganizationNodes.size()", equalTo(0));
    }

    /**
     * botocore pins OrganizationNodeType to ORGANIZATION / ORGANIZATIONAL_UNIT / ACCOUNT.
     * An unmodelled value used to filter to nothing and report an empty node list, so a
     * caller with a typo could not tell "no access" from "wrong parameter".
     */
    @Test
    void listOrganizationPortfolioAccess_unknownNodeType_returnsInvalidParameters() {
        String portfolioId = createPortfolio("ab-org-access-badtype");
        call("ListOrganizationPortfolioAccess", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"OrganizationNodeType\":\"ORG_UNIT\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", org.hamcrest.Matchers.startsWith("OrganizationNodeType must be one of"));
    }

    @Test
    void listOrganizationPortfolioAccess_missingNodeType_returnsInvalidParameters() {
        String portfolioId = createPortfolio("ab-org-access-missing-type");
        call("ListOrganizationPortfolioAccess", "{\"PortfolioId\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }
}
