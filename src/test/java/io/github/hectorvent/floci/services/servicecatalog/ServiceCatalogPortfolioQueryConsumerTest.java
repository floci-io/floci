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
 * Wire-level tests for {@code ListAcceptedPortfolioShares},
 * {@code ListConstraintsForPortfolio} and {@code ListLaunchPaths}. Last three
 * operations in this session's servicecatalog full-parity batch (56 ops total).
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). {@code listConstraintsForPortfolio} was a hardcoded stub
 * that always returned an empty list, despite {@code CreateConstraint} (already
 * {@code accepted}) fully persisting constraints with the exact
 * {@code PortfolioId}/{@code ProductId} fields this query needed to filter on —
 * same shape as issue 0018 (`ListServiceActions`). Fixed to actually scan.
 * {@code ListAcceptedPortfolioShares} reuses {@code ListPortfolios}'s handler as-is
 * — consistent with the already-documented `AcceptPortfolioShare` limitation (no
 * distinct acceptance state is tracked), not a new bug.
 */
@QuarkusTest
class ServiceCatalogPortfolioQueryConsumerTest {

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

    private static String createProduct(String name) {
        return call("CreateProduct", "{\"Name\":\"" + name + "\",\"Owner\":\"floci-test\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
    }

    private static String createPortfolio(String displayName) {
        return call("CreatePortfolio", "{\"DisplayName\":\"" + displayName
                + "\",\"ProviderName\":\"floci-test\"}")
                .then().statusCode(200)
                .extract().path("PortfolioDetail.Id");
    }

    // ---------- ListAcceptedPortfolioShares ----------

    @Test
    void listAcceptedPortfolioShares_includesExistingPortfolio() {
        String portfolioId = createPortfolio("ab-accepted-shares-portfolio");

        call("ListAcceptedPortfolioShares", "{}")
        .then()
            .statusCode(200)
            .body("PortfolioDetails.Id", hasItem(portfolioId));
    }

    // ---------- ListConstraintsForPortfolio ----------

    @Test
    void listConstraintsForPortfolio_returnsCreatedConstraint() {
        String portfolioId = createPortfolio("ab-list-constraints-portfolio");
        String productId = createProduct("ab-list-constraints-product");
        String constraintId = call("CreateConstraint", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"ProductId\":\"" + productId + "\",\"Parameters\":\"{}\",\"Type\":\"LAUNCH\","
                + "\"IdempotencyToken\":\"tok-list-constraints\"}")
                .then().statusCode(200)
                .extract().path("ConstraintDetail.ConstraintId");

        call("ListConstraintsForPortfolio", "{\"PortfolioId\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(200)
            .body("ConstraintDetails.ConstraintId", hasItem(constraintId));
    }

    @Test
    void listConstraintsForPortfolio_scopedToProductId_excludesOtherProducts() {
        String portfolioId = createPortfolio("ab-list-constraints-scoped-portfolio");
        String productA = createProduct("ab-list-constraints-scoped-product-a");
        String productB = createProduct("ab-list-constraints-scoped-product-b");
        String constraintA = call("CreateConstraint", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"ProductId\":\"" + productA + "\",\"Parameters\":\"{}\",\"Type\":\"LAUNCH\","
                + "\"IdempotencyToken\":\"tok-scoped-a\"}")
                .then().statusCode(200)
                .extract().path("ConstraintDetail.ConstraintId");
        call("CreateConstraint", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"ProductId\":\"" + productB + "\",\"Parameters\":\"{}\",\"Type\":\"LAUNCH\","
                + "\"IdempotencyToken\":\"tok-scoped-b\"}")
        .then().statusCode(200);

        call("ListConstraintsForPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"ProductId\":\"" + productA + "\"}")
        .then()
            .statusCode(200)
            .body("ConstraintDetails.ConstraintId", hasItem(constraintA))
            .body("ConstraintDetails.size()", equalTo(1));
    }

    @Test
    void listConstraintsForPortfolio_unknownPortfolio_returnsResourceNotFound() {
        call("ListConstraintsForPortfolio", "{\"PortfolioId\":\"port-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ListLaunchPaths ----------

    @Test
    void listLaunchPaths_returnsPortfolioContainingProduct() {
        String portfolioId = createPortfolio("ab-launch-paths-portfolio");
        String productId = createProduct("ab-launch-paths-product");
        call("AssociateProductWithPortfolio", "{\"ProductId\":\"" + productId
                + "\",\"PortfolioId\":\"" + portfolioId + "\"}")
        .then().statusCode(200);

        call("ListLaunchPaths", "{\"ProductId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("LaunchPathSummaries.Id", hasItem(portfolioId));
    }

    @Test
    void listLaunchPaths_missingProductId_returnsInvalidParameters() {
        call("ListLaunchPaths", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }
}
