package io.floci.aws.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * A REST API's {@code minimumCompressionSize}: a nullable integer between 0 and 10485760 that is
 * kept on create and on a {@code /minimumCompressionSize} patch, reported by GetRestApi, and
 * cleared by a patch with no value. Terraform's {@code aws_api_gateway_rest_api} reads it back as
 * {@code minimum_compression_size}, so a dropped value shows a change on every plan.
 *
 * @see <a href="https://docs.aws.amazon.com/apigateway/latest/api/API_RestApi.html">RestApi</a>
 */
@QuarkusTest
class ApiGatewayMinimumCompressionSizeIntegrationTest {

    private static final String MEMBER = "minimumCompressionSize";
    private static final String OUT_OF_RANGE = "Invalid minimum compression size, must be between 0 and 10485760";

    private final List<String> apiIds = new ArrayList<>();

    @AfterEach
    void deleteApis() {
        apiIds.forEach(id -> given().delete("/restapis/" + id).then().statusCode(202));
        apiIds.clear();
    }

    private ValidatableResponse create(Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body).post("/restapis").then();
    }

    private String createApi(String name, Object minimumCompressionSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put(MEMBER, minimumCompressionSize);
        return createApi(body);
    }

    private String createApi(Map<String, Object> body) {
        String id = create(body).statusCode(201).extract().path("id");
        apiIds.add(id);
        return id;
    }

    @SafeVarargs
    private ValidatableResponse patch(String apiId, Map<String, String>... operations) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("patchOperations", List.of(operations)))
                .patch("/restapis/" + apiId).then();
    }

    private static Map<String, String> replace(String path, String value) {
        Map<String, String> op = new HashMap<>();
        op.put("op", "replace");
        op.put("path", path);
        op.put("value", value);
        return op;
    }

    /** A replace that carries no {@code value} key at all, as Terraform sends to turn compression off. */
    private static Map<String, String> replaceWithoutValue(String path) {
        return Map.of("op", "replace", "path", path);
    }

    private ValidatableResponse get(String apiId) {
        return given().get("/restapis/" + apiId).then().statusCode(200);
    }

    @Test
    void createRestApiKeepsTheSizeAndGetRestApiReportsIt() {
        Map<String, Object> body = Map.of("name", "compression-create", "minimumCompressionSize", 1024);
        String apiId = create(body).statusCode(201)
                .body("minimumCompressionSize", equalTo(1024))
                .extract().path("id");
        apiIds.add(apiId);

        get(apiId).body("minimumCompressionSize", equalTo(1024));
    }

    @Test
    void zeroIsKeptBecauseItEnablesCompressionForEveryPayload() {
        String apiId = createApi("compression-zero", 0);

        get(apiId).body("minimumCompressionSize", equalTo(0));
    }

    @Test
    void anApiCreatedWithoutASizeOmitsTheMember() {
        String omitted = createApi(Map.of("name", "compression-omitted"));
        String explicitNull = createApi("compression-null", null);

        get(omitted).body("$", not(hasKey(MEMBER)));
        get(explicitNull).body("$", not(hasKey(MEMBER)));
    }

    @Test
    void theBoundsOfTheRangeAreAccepted() {
        String apiId = createApi("compression-max", 10485760);

        get(apiId).body("minimumCompressionSize", equalTo(10485760));
    }

    @Test
    void aPatchReplacesTheSizeAndAPatchWithoutAValueClearsIt() {
        String apiId = createApi("compression-patch", null);

        patch(apiId, replace("/minimumCompressionSize", "2048")).statusCode(200)
                .body("minimumCompressionSize", equalTo(2048));
        get(apiId).body("minimumCompressionSize", equalTo(2048));

        // Terraform clears minimum_compression_size by leaving the value out of the operation.
        patch(apiId, replaceWithoutValue("/minimumCompressionSize")).statusCode(200)
                .body("$", not(hasKey(MEMBER)));
        get(apiId).body("$", not(hasKey(MEMBER)));

        // An explicit null or an empty value clears it the same way.
        patch(apiId, replace("/minimumCompressionSize", "512")).statusCode(200);
        patch(apiId, replace("/minimumCompressionSize", null)).statusCode(200)
                .body("$", not(hasKey(MEMBER)));
        patch(apiId, replace("/minimumCompressionSize", "512")).statusCode(200);
        patch(apiId, replace("/minimumCompressionSize", "")).statusCode(200)
                .body("$", not(hasKey(MEMBER)));
        get(apiId).body("$", not(hasKey(MEMBER)));
    }

    @Test
    void aSizeOutsideTheRangeIsRefusedOnCreate() {
        create(Map.of("name", "compression-negative", MEMBER, -1)).statusCode(400)
                .body("message", equalTo(OUT_OF_RANGE));
        create(Map.of("name", "compression-too-big", MEMBER, 10485761)).statusCode(400)
                .body("message", equalTo(OUT_OF_RANGE));
        create(Map.of("name", "compression-text", MEMBER, "big")).statusCode(400)
                .body("message", equalTo(OUT_OF_RANGE));
    }

    @Test
    void aRefusedPatchLeavesTheApiAsItWas() {
        String apiId = createApi("compression-refused", 1024);

        patch(apiId, replace("/minimumCompressionSize", "-1")).statusCode(400)
                .body("message", equalTo(OUT_OF_RANGE));
        patch(apiId, replace("/minimumCompressionSize", "10485761")).statusCode(400)
                .body("message", equalTo(OUT_OF_RANGE));
        patch(apiId, replace("/minimumCompressionSize", "1k")).statusCode(400)
                .body("message", equalTo(OUT_OF_RANGE));
        // The operations before the refused one in the same request are not applied either.
        patch(apiId, replace("/name", "renamed"), replace("/minimumCompressionSize", "1k")).statusCode(400);

        get(apiId).body("name", equalTo("compression-refused"), "minimumCompressionSize", equalTo(1024));
    }
}
