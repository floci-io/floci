package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class LambdaListFunctionsPaginationIntegrationTest {

    private static final String ACCOUNT = "000000006652";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/lambda/aws4_request";

    @Inject LambdaService service;

    @Test
    void listFunctionsHonorsMaxItemsAndMarker() {
        List<String> names = new ArrayList<>();
        String suffix = UUID.randomUUID().toString();
        String unrelatedName = "000-page-" + suffix;
        boolean unrelatedCreated = false;
        try {
            RequestScopes.runAs(DEFAULT_ACCOUNT, REGION, () -> service.createFunction(REGION, Map.of(
                    "FunctionName", unrelatedName,
                    "Runtime", "nodejs20.x",
                    "Role", "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler", "index.handler")));
            unrelatedCreated = true;
            for (String letter : List.of("b", "a", "c")) {
                String name = "issue-page-" + suffix + "-" + letter;
                lambdaRequest().contentType("application/json")
                        .body("""
                                {"FunctionName":"%s","Runtime":"nodejs20.x",
                                 "Role":"arn:aws:iam::%s:role/lambda-role",
                                 "Handler":"index.handler"}
                                """.formatted(name, ACCOUNT))
                        .post("/2015-03-31/functions").then().statusCode(201);
                names.add(name);
            }
            String firstMarker = lambdaRequest().queryParam("MaxItems", 1)
                    .get("/2015-03-31/functions").then().statusCode(200)
                    .body("Functions.FunctionName", contains(names.get(1)))
                    .body("NextMarker", notNullValue())
                    .extract().path("NextMarker");
            String secondMarker = lambdaRequest().queryParam("MaxItems", 1).queryParam("Marker", firstMarker)
                    .get("/2015-03-31/functions").then().statusCode(200)
                    .body("Functions.FunctionName", contains(names.get(0)))
                    .body("NextMarker", notNullValue())
                    .extract().path("NextMarker");
            lambdaRequest().queryParam("MaxItems", 1).queryParam("Marker", secondMarker)
                    .get("/2015-03-31/functions").then().statusCode(200)
                    .body("Functions.FunctionName", contains(names.get(2)))
                    .body("$", not(hasKey("NextMarker")));
        } finally {
            for (String name : names) {
                RequestScopes.runAs(ACCOUNT, REGION, () -> service.deleteFunction(REGION, name));
            }
            if (unrelatedCreated) {
                RequestScopes.runAs(DEFAULT_ACCOUNT, REGION,
                        () -> service.deleteFunction(REGION, unrelatedName));
            }
        }
    }

    @Test
    void listFunctionsRejectsAnUnknownMarker() {
        lambdaRequest().queryParam("Marker", "not-an-issued-marker")
                .get("/2015-03-31/functions").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid pagination key."));
    }

    @Test
    void listFunctionsRejectsOutOfRangeMaxItems() {
        for (int maxItems : List.of(0, 10001)) {
            lambdaRequest().queryParam("MaxItems", maxItems)
                    .get("/2015-03-31/functions").then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void listFunctionsReportsSerializationFailureBeforeMarkerValidation() {
        lambdaRequest().queryParam("MaxItems", "abc").queryParam("Marker", "not-an-issued-marker")
                .get("/2015-03-31/functions").then().statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("'abc' can not be converted to Integer"));
    }

    private static RequestSpecification lambdaRequest() {
        return given().header("Authorization", AUTHORIZATION);
    }
}
