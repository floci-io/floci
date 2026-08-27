package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level tests for {@code CreateConstraint}, {@code DescribeConstraint},
 * {@code UpdateConstraint} and {@code DeleteConstraint}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). {@code ConstraintId} and {@code Type} are asserted by value,
 * not just presence, because {@code describeConstraint}'s handler originally read
 * the stored object's fields under the wrong names ({@code Id}/{@code ConstraintType}
 * instead of {@code ConstraintId}/{@code Type}) and always returned empty strings —
 * a value-level assertion is what would have caught that.
 */
@QuarkusTest
class ServiceCatalogConstraintConsumerTest {

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

    private static String createConstraint(String portfolioId, String productId) {
        return call("CreateConstraint", "{\"PortfolioId\":\"" + portfolioId + "\",\"ProductId\":\""
                + productId + "\",\"Parameters\":\"{}\",\"Type\":\"LAUNCH\",\"IdempotencyToken\":\""
                + "tok-" + portfolioId + "\"}")
                .then().statusCode(200)
                .extract().path("ConstraintDetail.ConstraintId");
    }

    // ---------- CreateConstraint ----------

    @Test
    void createConstraint_returnsConstraintDetailMatchingRequest() {
        String portfolioId = createPortfolio("ab-constraint-portfolio");
        String productId = createProduct("ab-constraint-product");
        call("CreateConstraint", "{\"PortfolioId\":\"" + portfolioId + "\",\"ProductId\":\""
                + productId + "\",\"Parameters\":\"{}\",\"Type\":\"LAUNCH\",\"IdempotencyToken\":\"tok-1\"}")
        .then()
            .statusCode(200)
            .body("ConstraintDetail.Type", equalTo("LAUNCH"))
            .body("ConstraintDetail.PortfolioId", equalTo(portfolioId))
            .body("ConstraintDetail.ProductId", equalTo(productId))
            .body("ConstraintDetail.ConstraintId", org.hamcrest.Matchers.notNullValue());
    }

    // ---------- DescribeConstraint ----------

    @Test
    void describeConstraint_returnsConstraintIdAndType() {
        String portfolioId = createPortfolio("ab-describe-constraint-portfolio");
        String productId = createProduct("ab-describe-constraint-product");
        String constraintId = createConstraint(portfolioId, productId);

        call("DescribeConstraint", "{\"Id\":\"" + constraintId + "\"}")
        .then()
            .statusCode(200)
            .body("ConstraintDetail.ConstraintId", equalTo(constraintId))
            .body("ConstraintDetail.Type", equalTo("LAUNCH"))
            .body("ConstraintDetail.PortfolioId", equalTo(portfolioId));
    }

    @Test
    void describeConstraint_unknownId_returnsResourceNotFound() {
        call("DescribeConstraint", "{\"Id\":\"con-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- UpdateConstraint ----------

    @Test
    void updateConstraint_changesDescription() {
        String portfolioId = createPortfolio("ab-update-constraint-portfolio");
        String productId = createProduct("ab-update-constraint-product");
        String constraintId = createConstraint(portfolioId, productId);

        call("UpdateConstraint", "{\"Id\":\"" + constraintId + "\",\"Description\":\"updated\"}")
        .then()
            .statusCode(200)
            .body("ConstraintDetail.ConstraintId", equalTo(constraintId))
            .body("ConstraintDetail.Description", equalTo("updated"));

        call("DescribeConstraint", "{\"Id\":\"" + constraintId + "\"}")
        .then()
            .statusCode(200)
            .body("ConstraintDetail.Description", equalTo("updated"));
    }

    // ---------- DeleteConstraint ----------

    @Test
    void deleteConstraint_removesConstraint() {
        String portfolioId = createPortfolio("ab-delete-constraint-portfolio");
        String productId = createProduct("ab-delete-constraint-product");
        String constraintId = createConstraint(portfolioId, productId);

        call("DeleteConstraint", "{\"Id\":\"" + constraintId + "\"}")
        .then()
            .statusCode(200);

        call("DescribeConstraint", "{\"Id\":\"" + constraintId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
