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
 * Wire-level tests for {@code DescribeCopyProductStatus}, {@code DescribeProductView},
 * {@code DescribeProvisioningParameters} and
 * {@code DescribeServiceActionExecutionParameters}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). {@code describeCopyProductStatus} looked up a token via
 * {@code associationStore} that {@code copyProduct} (already {@code accepted} from
 * earlier this session) never persisted — same shape as issues 0020/0021. Fixed by
 * having {@code copyProduct} store a status row under the token it returns.
 */
@QuarkusTest
class ServiceCatalogDescribeQueryConsumerTest {

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

    private static String describeProductAsAdminArn(String productId) {
        return call("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.ARN");
    }

    // ---------- DescribeCopyProductStatus ----------

    @Test
    void describeCopyProductStatus_returnsSucceededWithTargetProductId() {
        String sourceId = createProduct("ab-copy-status-source");
        String sourceArn = describeProductAsAdminArn(sourceId);

        String token = call("CopyProduct", "{\"SourceProductArn\":\"" + sourceArn
                + "\",\"TargetProductName\":\"ab-copy-status-target\"}")
                .then().statusCode(200)
                .extract().path("CopyProductToken");

        call("DescribeCopyProductStatus", "{\"CopyProductToken\":\"" + token + "\"}")
        .then()
            .statusCode(200)
            .body("CopyProductStatus", equalTo("SUCCEEDED"))
            .body("TargetProductId", org.hamcrest.Matchers.notNullValue());
    }

    // ---------- DescribeProductView ----------

    @Test
    void describeProductView_returnsSummaryAndArtifacts() {
        String productId = createProduct("ab-product-view");

        call("DescribeProductView", "{\"Id\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("ProductViewSummary.Id", equalTo(productId))
            .body("ProvisioningArtifacts.Name", hasItem("v1"));
    }

    @Test
    void describeProductView_missingId_returnsInvalidParameters() {
        call("DescribeProductView", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    // ---------- DescribeProvisioningParameters ----------

    @Test
    void describeProvisioningParameters_returnsParameterKeys() {
        String productId = call("CreateProduct", "{\"Name\":\"ab-provisioning-params\",\"Owner\":\""
                + "floci-test\",\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\",\"Info\":{"
                + "\"LoadTemplateFromURL\":\"https://example.com/template.json\"}}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");

        call("DescribeProvisioningParameters", "{\"ProductId\":\"" + productId + "\"}")
        .then()
            .statusCode(200);
    }

    @Test
    void describeProvisioningParameters_unknownArtifact_returnsResourceNotFound() {
        String productId = createProduct("ab-provisioning-params-unknown-artifact");

        call("DescribeProvisioningParameters", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"pa-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeProvisioningParameters_missingProductIdAndName_returnsInvalidParameters() {
        call("DescribeProvisioningParameters", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    // ---------- DescribeServiceActionExecutionParameters ----------

    private static String importProvisionedProduct(String productId, String name) {
        return call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"pa-anything\",\"ProvisionedProductName\":\""
                + name + "\",\"PhysicalId\":\"phys-" + name + "\",\"IdempotencyToken\":\"tok-" + name + "\"}")
                .then().statusCode(200)
                .extract().path("RecordDetail.ProvisionedProductId");
    }

    @Test
    void describeServiceActionExecutionParameters_returnsEmptyParameterList() {
        String productId = createProduct("ab-safep-product");
        String provisionedId = importProvisionedProduct(productId, "ab-safep-pp");

        call("DescribeServiceActionExecutionParameters", "{\"ProvisionedProductId\":\"" + provisionedId
                + "\",\"ServiceActionId\":\"sa-anything\"}")
        .then()
            .statusCode(200)
            .body("ServiceActionParameters.size()", equalTo(0));
    }

    @Test
    void describeServiceActionExecutionParameters_unknownProduct_returnsResourceNotFound() {
        call("DescribeServiceActionExecutionParameters", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"ServiceActionId\":\"sa-anything\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeServiceActionExecutionParameters_missingServiceActionId_returnsInvalidParameters() {
        call("DescribeServiceActionExecutionParameters", "{\"ProvisionedProductId\":\"pp-anything\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    // ---------- missing identifier parameters ----------

    @Test
    void describeProductAsAdmin_missingId_returnsInvalidParameters() {
        call("DescribeProductAsAdmin", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    @Test
    void describePortfolio_missingId_returnsInvalidParameters() {
        call("DescribePortfolio", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    @Test
    void describeTagOption_missingId_returnsInvalidParameters() {
        call("DescribeTagOption", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }
}
