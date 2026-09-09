package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Wire-level tests for the five Service Catalog operations added together:
 * {@code DescribeProduct}, {@code DescribePortfolioShares},
 * {@code ListPortfoliosForProduct}, {@code CopyProduct} and
 * {@code DescribeProvisioningArtifact}.
 *
 * <p>Grouped in one new class rather than five. The convention of one class per
 * operation exists to stop a shared, pre-existing test from having its assertions
 * weakened; a brand-new class cannot weaken anything, and every test here targets a
 * single operation, so removing one dispatch case still fails only that operation's
 * tests.
 *
 * <p>Each operation drives the real HTTP route with the real {@code X-Amz-Target},
 * so the dispatch case is itself under test (CS-001).
 */
@QuarkusTest
class ServiceCatalogCatalogReadOpsConsumerTest {

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

    /** Creates a product and returns its id, so each test owns its fixture. */
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

    // ---------- DescribeProduct ----------

    @Test
    void describeProduct_returnsSummaryAndArtifacts() {
        String productId = createProduct("ab-describe-product");
        call("DescribeProduct", "{\"Id\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("ProductViewSummary.Id", equalTo(productId))
            .body("ProductViewSummary.Name", equalTo("ab-describe-product"))
            .body("ProvisioningArtifacts[0].Id", startsWith("pa-"))
            .body("ProvisioningArtifacts[0].Name", equalTo("v1"));
    }

    /** DescribeProduct must not return the admin shape; the two differ on the wire. */
    @Test
    void describeProduct_doesNotReturnAdminOnlyProductViewDetail() {
        String productId = createProduct("ab-describe-product-shape");
        call("DescribeProduct", "{\"Id\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("ProductViewDetail", equalTo(null))
            .body("ProvisioningArtifactDetails", equalTo(null));
    }

    @Test
    void describeProduct_unknownId_returnsResourceNotFound() {
        call("DescribeProduct", "{\"Id\":\"prod-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- DescribePortfolioShares ----------

    @Test
    void describePortfolioShares_returnsSharesCreatedForThePortfolio() {
        String portfolioId = createPortfolio("ab-shares-portfolio");
        call("CreatePortfolioShare", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"AccountId\":\"222222222222\"}").then().statusCode(200);

        call("DescribePortfolioShares", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"Type\":\"ACCOUNT\"}")
        .then()
            .statusCode(200)
            .body("PortfolioShareDetails.PrincipalId", hasItem("222222222222"))
            .body("PortfolioShareDetails.Type", hasItem("ACCOUNT"))
            .body("PortfolioShareDetails[0].Accepted", equalTo(true));
    }

    /** A portfolio with no shares is an empty list, not an error. */
    @Test
    void describePortfolioShares_noShares_returnsEmptyList() {
        String portfolioId = createPortfolio("ab-shares-empty");
        call("DescribePortfolioShares", "{\"PortfolioId\":\"" + portfolioId + "\"}")
        .then()
            .statusCode(200)
            .body("PortfolioShareDetails.size()", equalTo(0));
    }

    @Test
    void describePortfolioShares_unknownPortfolio_returnsResourceNotFound() {
        call("DescribePortfolioShares", "{\"PortfolioId\":\"port-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * Asserts the message, not just the type. The handler's default arm also returns
     * InvalidParametersException at 400, so a type-only assertion would pass even with
     * the dispatch case deleted and would prove nothing about this operation (CS-001).
     */
    @Test
    void describePortfolioShares_missingPortfolioId_returnsInvalidParameters() {
        call("DescribePortfolioShares", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", equalTo("PortfolioId is required"));
    }

    /**
     * {@code Type} selects which share flavour to report and botocore pins it to
     * {@code DescribePortfolioShareType}. An unmodelled value used to match nothing and
     * return an empty list — a client filtering on a typo saw "no shares", not an error.
     */
    @Test
    void describePortfolioShares_unknownType_returnsInvalidParameters() {
        String portfolioId = createPortfolio("ab-shares-badtype");
        call("DescribePortfolioShares", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"Type\":\"ACCOUNTS\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", startsWith("Type must be one of"));
    }

    // ---------- CreatePortfolioShare ----------

    /**
     * The share's store key is built from {@code OrganizationNode.Type}, so an unmodelled
     * type used to create a share DescribePortfolioShares then reported back verbatim.
     */
    @Test
    void createPortfolioShare_unknownOrganizationNodeType_returnsInvalidParameters() {
        String portfolioId = createPortfolio("ab-share-badnode");
        call("CreatePortfolioShare", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"OrganizationNode\":{\"Type\":\"TEAM\",\"Value\":\"o-abcdefghij\"}}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", startsWith("OrganizationNode.Type must be one of"));
    }

    /** botocore pins AccountId to ^[0-9]{12}$; a short id used to become a share key. */
    @Test
    void createPortfolioShare_malformedAccountId_returnsInvalidParameters() {
        String portfolioId = createPortfolio("ab-share-badaccount");
        call("CreatePortfolioShare", "{\"PortfolioId\":\"" + portfolioId
                + "\",\"AccountId\":\"2222\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", startsWith("AccountId must be a 12-digit"));
    }

    // ---------- ListPortfoliosForProduct ----------

    @Test
    void listPortfoliosForProduct_returnsAssociatedPortfolioOnly() {
        String productId = createProduct("ab-lpfp-product");
        String associated = createPortfolio("ab-lpfp-associated");
        String unrelated = createPortfolio("ab-lpfp-unrelated");
        call("AssociateProductWithPortfolio", "{\"PortfolioId\":\"" + associated
                + "\",\"ProductId\":\"" + productId + "\"}").then().statusCode(200);

        Response response = call("ListPortfoliosForProduct", "{\"ProductId\":\"" + productId + "\"}");
        response.then()
            .statusCode(200)
            .body("PortfolioDetails.Id", hasItem(associated));

        assertNotEquals(unrelated, response.path("PortfolioDetails[0].Id"),
                "an unassociated portfolio must not be returned");
        assertEquals(1, (Integer) response.path("PortfolioDetails.size()"));
    }

    @Test
    void listPortfoliosForProduct_noAssociations_returnsEmptyList() {
        String productId = createProduct("ab-lpfp-none");
        call("ListPortfoliosForProduct", "{\"ProductId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("PortfolioDetails.size()", equalTo(0));
    }

    @Test
    void listPortfoliosForProduct_unknownProduct_returnsResourceNotFound() {
        call("ListPortfoliosForProduct", "{\"ProductId\":\"prod-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- CopyProduct ----------

    @Test
    void copyProduct_createsIndependentCopyAndReturnsToken() {
        String sourceId = createProduct("ab-copy-source");
        String sourceArn = call("DescribeProductAsAdmin", "{\"Id\":\"" + sourceId + "\"}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.ARN");

        String token = call("CopyProduct", "{\"SourceProductArn\":\"" + sourceArn
                + "\",\"TargetProductName\":\"ab-copy-target\"}")
                .then().statusCode(200)
                .body("CopyProductToken", notNullValue())
                .extract().path("CopyProductToken");
        assertEquals(true, token.startsWith("copy-"), "token was: " + token);

        // The copy is a distinct product carrying the target name.
        Response search = call("SearchProductsAsAdmin", "{}");
        search.then().statusCode(200)
            .body("ProductViewDetails.ProductViewSummary.Name", hasItem("ab-copy-target"))
            .body("ProductViewDetails.ProductViewSummary.Name", hasItem("ab-copy-source"));
    }

    @Test
    void copyProduct_withoutTargetName_keepsSourceName() {
        String sourceId = createProduct("ab-copy-keepname");
        String sourceArn = call("DescribeProductAsAdmin", "{\"Id\":\"" + sourceId + "\"}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.ARN");
        call("CopyProduct", "{\"SourceProductArn\":\"" + sourceArn + "\"}")
        .then()
            .statusCode(200)
            .body("CopyProductToken", startsWith("copy-"));
    }

    /** Message-level assertion for the same reason as the DescribePortfolioShares case. */
    @Test
    void copyProduct_missingSourceArn_returnsInvalidParameters() {
        call("CopyProduct", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", equalTo("SourceProductArn is required"));
    }

    @Test
    void copyProduct_unknownSourceProduct_returnsResourceNotFound() {
        call("CopyProduct", "{\"SourceProductArn\":"
                + "\"arn:aws:catalog:us-east-1:000000000000:product/prod-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- DescribeProvisioningArtifact ----------

    @Test
    void describeProvisioningArtifact_byProductAndArtifactId_returnsDetail() {
        String productId = createProduct("ab-dpa-product");
        String artifactId = call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProvisioningArtifactDetails[0].Id");

        call("DescribeProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisioningArtifactDetail.Id", equalTo(artifactId))
            .body("ProvisioningArtifactDetail.Name", equalTo("v1"))
            .body("ProvisioningArtifactDetail.Type", equalTo("CLOUD_FORMATION_TEMPLATE"))
            .body("ProvisioningArtifactDetail.Active", equalTo(true))
            .body("Status", equalTo("AVAILABLE"));
    }

    @Test
    void describeProvisioningArtifact_unknownArtifact_returnsResourceNotFound() {
        String productId = createProduct("ab-dpa-unknown");
        call("DescribeProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"pa-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
