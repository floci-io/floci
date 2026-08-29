package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Wire-level tests for {@code ProvisionProduct}'s request validation.
 *
 * <p>The operation used to ignore {@code ProductId} and {@code ProductName}
 * entirely: any request carrying Account Factory provisioning parameters took the
 * Control Tower path, so an unrecognised product id still created a real
 * Organizations member account and recorded it under the seeded Account Factory
 * identifiers. These tests pin the three outcomes the request now has to choose
 * between — Account Factory, a plain stored product, and rejection.
 */
@QuarkusTest
class ServiceCatalogProvisionProductValidationConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String target, String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", target + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    private static Response call(String action, String body) {
        return call("AWS242ServiceCatalogService.", action, body);
    }

    private static Response organizations(String action, String body) {
        return call("AWSOrganizationsV20161128.", action, body);
    }

    /** The organization is shared across the service-catalog suite; creating it twice is expected to fail. */
    private static void ensureOrganization() {
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}");
    }

    private static void assertNoAccountWithEmail(String email) {
        organizations("ListAccounts", "{}")
        .then()
            .statusCode(200)
            .body("Accounts.Email", not(hasItem(email)));
    }

    private static String createProduct(String name) {
        return call("CreateProduct", "{\"Name\":\"" + name + "\",\"Owner\":\"floci-test\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
    }

    private static String firstArtifactId(String productId) {
        return call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProvisioningArtifactDetails[0].Id");
    }

    private static String accountFactoryParameters(String name, String email) {
        return "\"ProvisionedProductName\":\"" + name + "\",\"ProvisionToken\":\"tok-" + name + "\","
                + "\"ProvisioningParameters\":[{\"Key\":\"AccountName\",\"Value\":\"" + name + "\"},"
                + "{\"Key\":\"AccountEmail\",\"Value\":\"" + email + "\"}]";
    }

    @Test
    void provisionProduct_unknownProductId_returnsResourceNotFoundAndCreatesNoAccount() {
        ensureOrganization();
        String email = "ab-unknown-product@floci.test";

        call("ProvisionProduct", "{\"ProductId\":\"prod-doesnotexist\","
                + "\"ProvisioningArtifactId\":\"pa-doesnotexist\","
                + accountFactoryParameters("ab-provision-unknown-product", email) + "}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        assertNoAccountWithEmail(email);
    }

    @Test
    void provisionProduct_artifactIdAsProductId_returnsResourceNotFoundAndCreatesNoAccount() {
        ensureOrganization();
        String email = "ab-artifact-as-product@floci.test";

        call("ProvisionProduct", "{\"ProductId\":\"" + ServiceCatalogService.CONTROL_TOWER_ARTIFACT_ID + "\","
                + accountFactoryParameters("ab-provision-artifact-as-product", email) + "}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        assertNoAccountWithEmail(email);
    }

    @Test
    void provisionProduct_ambiguousProductName_returnsDuplicateResourceAndCreatesNoAccount() {
        // A user-created product may share its Name with another (names are not
        // unique keys); first-match would nondeterministically pick one — including
        // the Account Factory product. Ambiguity must fail, not guess.
        ensureOrganization();
        String email = "ab-ambiguous-name@floci.test";
        createProduct("AmbiguousName");
        createProduct("AmbiguousName");

        call("ProvisionProduct", "{\"ProductName\":\"AmbiguousName\","
                + accountFactoryParameters("ab-provision-ambiguous", email) + "}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DuplicateResourceException"));

        assertNoAccountWithEmail(email);
    }

    @Test
    void provisionProduct_withoutProductIdentifier_returnsInvalidParametersAndCreatesNoAccount() {
        ensureOrganization();
        String email = "ab-no-product-identifier@floci.test";

        call("ProvisionProduct", "{\"ProvisioningArtifactId\":\"pa-doesnotexist\","
                + accountFactoryParameters("ab-provision-no-identifier", email) + "}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));

        assertNoAccountWithEmail(email);
    }

    @Test
    void provisionProduct_artifactFromAnotherProduct_returnsResourceNotFound() {
        String productId = createProduct("ab-provision-artifact-check");

        call("ProvisionProduct", "{\"ProductId\":\"" + productId + "\","
                + "\"ProvisioningArtifactId\":\"pa-doesnotexist\","
                + accountFactoryParameters("ab-provision-wrong-artifact", "ab-wrong-artifact@floci.test") + "}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void provisionProduct_storedProduct_recordsThatProductWithoutCreatingAccount() {
        ensureOrganization();
        String productId = createProduct("ab-provision-plain-product");
        String artifactId = firstArtifactId(productId);
        String email = "ab-plain-product@floci.test";

        call("ProvisionProduct", "{\"ProductId\":\"" + productId + "\","
                + "\"ProvisioningArtifactId\":\"" + artifactId + "\","
                + accountFactoryParameters("ab-provision-plain", email) + "}")
        .then()
            .statusCode(200)
            .body("RecordDetail.Status", equalTo("SUCCEEDED"));

        call("DescribeProvisionedProduct", "{\"Name\":\"ab-provision-plain\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductDetail.ProductId", equalTo(productId))
            .body("ProvisionedProductDetail.ProvisioningArtifactId", equalTo(artifactId))
            .body("ProvisionedProductDetail.Type", not(equalTo("CONTROL_TOWER_ACCOUNT")));

        assertNoAccountWithEmail(email);

        call("TerminateProvisionedProduct", "{\"ProvisionedProductName\":\"ab-provision-plain\","
                + "\"TerminateToken\":\"tok-terminate-plain\"}")
        .then()
            .statusCode(200);
    }

    /**
     * Without {@code AccountEmail} the Account Factory path used to fall through to
     * Organizations' {@code validateEmail}, which throws {@code InvalidInputException} — an
     * Organizations shape, not a Service Catalog one, so a servicecatalog SDK client cannot
     * deserialize it into a typed exception.
     */
    @Test
    void provisionProduct_accountFactoryWithoutAccountEmail_returnsServiceCatalogInvalidParameters() {
        ensureOrganization();

        call("ProvisionProduct", "{\"ProductId\":\"" + ServiceCatalogService.CONTROL_TOWER_PRODUCT_ID + "\","
                + "\"ProvisioningArtifactId\":\"" + ServiceCatalogService.CONTROL_TOWER_ARTIFACT_ID + "\","
                + "\"ProvisionedProductName\":\"ab-provision-no-email\",\"ProvisionToken\":\"tok-no-email\","
                + "\"ProvisioningParameters\":[{\"Key\":\"AccountName\",\"Value\":\"ab-provision-no-email\"}]}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    /**
     * The guard belongs on the Account Factory path only — a plain stored product needs no
     * email at all, so provisioning one without {@code AccountEmail} must still succeed.
     */
    @Test
    void provisionProduct_plainProductWithoutAccountEmail_stillSucceeds() {
        String productId = createProduct("ab-provision-no-email-plain-product");
        String artifactId = firstArtifactId(productId);

        call("ProvisionProduct", "{\"ProductId\":\"" + productId + "\","
                + "\"ProvisioningArtifactId\":\"" + artifactId + "\","
                + "\"ProvisionedProductName\":\"ab-provision-no-email-plain\","
                + "\"ProvisionToken\":\"tok-no-email-plain\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.Status", equalTo("SUCCEEDED"));

        call("TerminateProvisionedProduct", "{\"ProvisionedProductName\":\"ab-provision-no-email-plain\","
                + "\"TerminateToken\":\"tok-terminate-no-email-plain\"}")
        .then()
            .statusCode(200);
    }

    @Test
    void provisionProduct_accountFactoryProduct_stillCreatesAccount() {
        ensureOrganization();
        String email = "ab-account-factory@floci.test";

        call("ProvisionProduct", "{\"ProductId\":\"" + ServiceCatalogService.CONTROL_TOWER_PRODUCT_ID + "\","
                + "\"ProvisioningArtifactId\":\"" + ServiceCatalogService.CONTROL_TOWER_ARTIFACT_ID + "\","
                + accountFactoryParameters("ab-provision-account-factory", email) + "}")
        .then()
            .statusCode(200)
            .body("RecordDetail.Status", equalTo("SUCCEEDED"));

        call("DescribeProvisionedProduct", "{\"Name\":\"ab-provision-account-factory\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductDetail.Type", equalTo("CONTROL_TOWER_ACCOUNT"))
            .body("ProvisionedProductDetail.ProductId",
                    equalTo(ServiceCatalogService.CONTROL_TOWER_PRODUCT_ID));

        organizations("ListAccounts", "{}")
        .then()
            .statusCode(200)
            .body("Accounts.Email", hasItem(email));

        call("TerminateProvisionedProduct", "{\"ProvisionedProductName\":\"ab-provision-account-factory\","
                + "\"TerminateToken\":\"tok-terminate-account-factory\"}")
        .then()
            .statusCode(200);
    }
}
