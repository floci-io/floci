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
 * Wire-level tests for {@code AssociateBudgetWithResource},
 * {@code DisassociateBudgetFromResource} and {@code ListBudgetsForResource}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001): removing one operation's dispatch case must fail only that
 * operation's own tests. Assertions on error paths check the exception {@code __type},
 * not just status code, since {@code AssociateBudgetWithResource}'s duplicate-association
 * error and the handler's unsupported-operation default arm are both 400.
 */
@QuarkusTest
class ServiceCatalogBudgetAssociationConsumerTest {

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

    // ---------- AssociateBudgetWithResource ----------

    @Test
    void associateBudgetWithResource_returnsEmptyBody() {
        String productId = createProduct("ab-budget-associate");
        call("AssociateBudgetWithResource", "{\"BudgetName\":\"my-budget\",\"ResourceId\":\""
                + productId + "\"}")
        .then()
            .statusCode(200);
    }

    @Test
    void associateBudgetWithResource_duplicate_returnsDuplicateResourceException() {
        String productId = createProduct("ab-budget-dup");
        call("AssociateBudgetWithResource", "{\"BudgetName\":\"dup-budget\",\"ResourceId\":\""
                + productId + "\"}")
        .then().statusCode(200);

        call("AssociateBudgetWithResource", "{\"BudgetName\":\"dup-budget\",\"ResourceId\":\""
                + productId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DuplicateResourceException"));
    }

    @Test
    void associateBudgetWithResource_unknownResource_returnsResourceNotFound() {
        call("AssociateBudgetWithResource", "{\"BudgetName\":\"my-budget\",\"ResourceId\":\""
                + "prod-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- DisassociateBudgetFromResource ----------

    @Test
    void disassociateBudgetFromResource_removesAssociation() {
        String productId = createProduct("ab-budget-disassociate");
        call("AssociateBudgetWithResource", "{\"BudgetName\":\"remove-me\",\"ResourceId\":\""
                + productId + "\"}")
        .then().statusCode(200);

        call("DisassociateBudgetFromResource", "{\"BudgetName\":\"remove-me\",\"ResourceId\":\""
                + productId + "\"}")
        .then()
            .statusCode(200);

        call("ListBudgetsForResource", "{\"ResourceId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("Budgets.BudgetName.size()", equalTo(0));
    }

    // ---------- ListBudgetsForResource ----------

    @Test
    void listBudgetsForResource_returnsAssociatedBudgets() {
        String productId = createProduct("ab-budget-list");
        call("AssociateBudgetWithResource", "{\"BudgetName\":\"listed-budget\",\"ResourceId\":\""
                + productId + "\"}")
        .then().statusCode(200);

        call("ListBudgetsForResource", "{\"ResourceId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("Budgets.BudgetName", hasItem("listed-budget"));
    }

    @Test
    void listBudgetsForResource_unknownResource_returnsResourceNotFound() {
        call("ListBudgetsForResource", "{\"ResourceId\":\"prod-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
