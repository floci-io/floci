package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Json11CanonicalRegionIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String REMOTE_REGION = "eu-west-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"events,query", "ssm,query", "events,blank-header", "ssm,blank-header",
            "events,header", "ssm,header", "events,missing-algorithm", "ssm,missing-algorithm",
            "events,default", "ssm,default", "events,unrecognized-header", "ssm,unrecognized-header"})
    void dispatchUsesTheCanonicalCredentialRegion(String service, String mode) {
        String name = "json11-scope-" + UUID.randomUUID();
        String expectedRegion = mode.equals("query") || mode.equals("blank-header")
                ? REMOTE_REGION : DEFAULT_REGION;
        String otherRegion = expectedRegion.equals(DEFAULT_REGION) ? REMOTE_REGION : DEFAULT_REGION;
        for (String region : List.of(DEFAULT_REGION, REMOTE_REGION)) {
            cleanup.register(() -> {
                ValidatableResponse deleted = call(service,
                        service.equals("events") ? "DeleteEventBus" : "DeleteParameter", Map.of("Name", name), region);
                if (deleted.extract().statusCode() == 400) {
                    deleted.body("__type", equalTo(notFound(service)));
                } else {
                    deleted.statusCode(200);
                }
                call(service, service.equals("events") ? "DescribeEventBus" : "GetParameter",
                        Map.of("Name", name), region).statusCode(400).body("__type", equalTo(notFound(service)));
            });
        }
        RequestSpecification request = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", target(service, service.equals("events") ? "CreateEventBus" : "PutParameter"));
        if (!mode.equals("default")) {
            request.queryParam("X-Amz-Credential", ACCOUNT + "/20261015/" + REMOTE_REGION
                            + "/" + service + "/aws4_request")
                    .queryParam("X-Amz-Signature", "abc");
            if (!mode.equals("missing-algorithm")) {
                request.queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
            }
        }
        if (mode.equals("blank-header")) {
            request.header("Authorization", "");
        } else if (mode.equals("header")) {
            request.header("Authorization", auth(service, DEFAULT_REGION));
        } else if (mode.equals("unrecognized-header")) {
            request.header("Authorization", "Bearer unrecognized");
        }
        request.body(service.equals("events") ? Map.of("Name", name)
                : Map.of("Name", name, "Value", "selected-region", "Type", "String"))
                .when().post("/").then().statusCode(200);

        String read = service.equals("events") ? "DescribeEventBus" : "GetParameter";
        call(service, read, Map.of("Name", name), expectedRegion).statusCode(200)
                .body(service.equals("events") ? "Name" : "Parameter.Value",
                        equalTo(service.equals("events") ? name : "selected-region"));
        call(service, read, Map.of("Name", name), otherRegion).statusCode(400)
                .body("__type", equalTo(notFound(service)));
    }

    private static ValidatableResponse call(String service, String action, Map<String, Object> body, String region) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", auth(service, region))
                .header("X-Amz-Target", target(service, action)).body(body)
                .when().post("/").then();
    }

    private static String notFound(String service) {
        return service.equals("events") ? "ResourceNotFoundException" : "ParameterNotFound";
    }

    private static String target(String service, String action) {
        return (service.equals("events") ? "AWSEvents." : "AmazonSSM.") + action;
    }

    private static String auth(String service, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261015/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
