package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import static io.github.hectorvent.floci.core.common.AwsJson11Controller.CONTENT_TYPE_AWS_JSON_1_1;
import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;

/**
 * Registers a global RestAssured filter that rewrites AWS-specific JSON content types
 * to standard application/json before response parsing.
 * <p>
 * Changes EncoderConfig to allow AWS-specific content types to be treated as JSON for request encoding.
 * <p>
 * Note: RestAssured.registerParser() and RestAssured.defaultParser do not work reliably
 * under Quarkus @QuarkusTest because the ResponseParserRegistrar state does not propagate
 * correctly to RestAssured's internal Groovy-based response parsing. The filter approach
 * modifies the response content type directly, bypassing this issue.
 */
public class RestAssuredJsonUtils {

    private static final AwsContentTypeFilter AWS_CONTENT_TYPE_FILTER = new AwsContentTypeFilter();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RestAssuredJsonUtils() {
        // Utility class, prevent instantiation
    }

    public static void configureAwsContentTypes() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(CONTENT_TYPE_AWS_JSON_1_0, ContentType.JSON)
                        .encodeContentTypeAs(CONTENT_TYPE_AWS_JSON_1_1, ContentType.JSON));

        if (!RestAssured.filters().contains(AWS_CONTENT_TYPE_FILTER)) {
            RestAssured.filters(AWS_CONTENT_TYPE_FILTER);
        }
    }

    public static Response awsAction(String target, String action, String body) {
        return given()
                .header("X-Amz-Target", target + "." + action)
                .contentType(CONTENT_TYPE_AWS_JSON_1_1)
                .body(body)
                .when()
                .post("/");
    }

    public static JsonNode awsActionJson(String target, String action, String body) throws Exception {
        String response = awsAction(target, action, body)
                .then()
                .statusCode(200)
                .extract()
                .asString();
        return OBJECT_MAPPER.readTree(response);
    }

}

/**
 * RestAssured filter that rewrites AWS-specific JSON content types
 * (e.g. application/x-amz-json-1.0) to standard application/json.
 * <p>
 * This is necessary because RestAssured.registerParser() does not work
 * reliably under Quarkus @QuarkusTest due to classloader isolation between
 * the test class and RestAssured's internal Groovy-based response parsing.
 */
class AwsContentTypeFilter implements Filter {

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {
        Response response = ctx.next(requestSpec, responseSpec);
        String contentType = response.contentType();
        if (contentType != null && contentType.contains("x-amz-json")) {
            return new io.restassured.builder.ResponseBuilder()
                    .clone(response)
                    .setContentType(contentType.replaceFirst("application/x-amz-json-[0-9.]+", "application/json"))
                    .build();
        }
        return response;
    }
}
