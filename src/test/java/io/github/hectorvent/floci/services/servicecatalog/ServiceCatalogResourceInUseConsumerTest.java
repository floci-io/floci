package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level tests for the {@code ResourceInUseException} guard on
 * {@code DeletePortfolio} and {@code DeleteProduct}.
 *
 * <p>Both operations used to delete the resource and then cascade — removing every
 * association naming the portfolio and every share keyed to it. AWS does the opposite
 * and refuses the call: "You cannot delete a portfolio if it was shared with you or if
 * it has associated products, users, constraints, or shared accounts", and
 * "You cannot delete a product if it was shared with you or is associated with a
 * portfolio". Both model {@code ResourceInUseException}. The old behaviour turned a
 * recoverable no-op into unrecoverable data loss reported as 200, which is what these
 * tests pin.
 */
@QuarkusTest
class ServiceCatalogResourceInUseConsumerTest {

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

    private static String createPortfolio(String name) {
        return call("CreatePortfolio", "{\"DisplayName\":\"" + name + "\",\"ProviderName\":\"floci-test\"}")
                .then().statusCode(200)
                .extract().path("PortfolioDetail.Id");
    }

    private static String createProduct(String name) {
        return call("CreateProduct", "{\"Name\":\"" + name + "\",\"Owner\":\"floci-test\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
    }

    // ---------- DeletePortfolio ----------

    @Test
    void deletePortfolio_withAssociatedProduct_returnsResourceInUseAndKeepsPortfolio() {
        String portfolioId = createPortfolio("ab-inuse-portfolio-product");
        String productId = createProduct("ab-inuse-portfolio-product-prod");
        call("AssociateProductWithPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"ProductId\":\"" + productId + "\"}").then().statusCode(200);

        call("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));

        call("DescribePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(200)
            .body("PortfolioDetail.Id", equalTo(portfolioId));

        // the association the old cascade would have destroyed is still there
        call("ListPortfoliosForProduct", "{\"ProductId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("PortfolioDetails.Id", org.hamcrest.Matchers.hasItem(portfolioId));
    }

    @Test
    void deletePortfolio_withAssociatedPrincipal_returnsResourceInUse() {
        String portfolioId = createPortfolio("ab-inuse-portfolio-principal");
        call("AssociatePrincipalWithPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"PrincipalARN\":\"arn:aws:iam::000000000000:role/ab-inuse\","
                + "\"PrincipalType\":\"IAM\"}").then().statusCode(200);

        call("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    void deletePortfolio_withSharedAccount_returnsResourceInUse() {
        String portfolioId = createPortfolio("ab-inuse-portfolio-share");
        call("CreatePortfolioShare", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"AccountId\":\"111111111111\"}").then().statusCode(200);

        call("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    void deletePortfolio_withNothingAssociated_deletesIt() {
        String portfolioId = createPortfolio("ab-inuse-portfolio-clean");

        call("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(200);

        call("DescribePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- DeleteProduct ----------

    @Test
    void deleteProduct_associatedWithPortfolio_returnsResourceInUseAndKeepsProduct() {
        String portfolioId = createPortfolio("ab-inuse-product-portfolio");
        String productId = createProduct("ab-inuse-product-associated");
        call("AssociateProductWithPortfolio", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"ProductId\":\"" + productId + "\"}").then().statusCode(200);

        call("DeleteProduct", "{\"Id\":\"" + productId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));

        call("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("ProductViewDetail.ProductViewSummary.Id", equalTo(productId));
    }

    @Test
    void deleteProduct_withNoPortfolioAssociation_deletesIt() {
        String productId = createProduct("ab-inuse-product-clean");

        call("DeleteProduct", "{\"Id\":\"" + productId + "\"}")
        .then()
            .statusCode(200);

        call("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
