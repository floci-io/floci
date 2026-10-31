package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Wire-level lifecycle and error coverage for function code-signing attachments. */
@QuarkusTest
class LambdaCodeSigningAttachmentIntegrationTest {

    private static final String CONFIGS = "/2020-04-22/code-signing-configs";
    private static final String SIGNING = "/2020-06-30";
    private static final String LEGACY_FUNCTIONS = "/2015-03-31/functions";
    private static final String PROFILE =
            "arn:aws:signer:us-east-1:000000000000:/signing-profiles/prof/AbCdEfGhIj";

    @Test
    void attachReadListDetachAndDeleteConfiguration() {
        String functionName = "signing-lifecycle-" + System.nanoTime();
        String functionPath = LEGACY_FUNCTIONS + "/" + functionName;
        String arn = createConfig();
        try {
            createFunction(functionName);
            given().contentType("application/json")
                    .body("{\"CodeSigningConfigArn\":\"" + arn + "\"}")
                    .when().put(SIGNING + "/functions/" + functionName + "/code-signing-config")
                    .then().statusCode(200)
                    .body("CodeSigningConfigArn", is(arn))
                    .body("FunctionName", is(functionName));

            given().when().get(SIGNING + "/functions/" + functionName + "/code-signing-config")
                    .then().statusCode(200)
                    .body("CodeSigningConfigArn", is(arn))
                    .body("FunctionName", is(functionName));
            given().when().get(functionPath + "/code-signing-config")
                    .then().statusCode(404);

            given().queryParam("MaxItems", 1)
                    .when().get(CONFIGS + "/" + arn + "/functions")
                    .then().statusCode(200)
                    .body("FunctionArns", contains(
                            "arn:aws:lambda:us-east-1:000000000000:function:" + functionName))
                    .body("NextMarker", nullValue());

            given().when().delete(CONFIGS + "/" + arn).then().statusCode(409)
                    .body("__type", is("ResourceConflictException"));

            given().when().delete(SIGNING + "/functions/" + functionName + "/code-signing-config")
                    .then().statusCode(204);
            given().when().get(SIGNING + "/functions/" + functionName + "/code-signing-config")
                    .then().statusCode(200)
                    .body("CodeSigningConfigArn", is(""));
            given().when().delete(CONFIGS + "/" + arn).then().statusCode(204);
        } finally {
            try {
                given().when().delete(functionPath);
            } finally {
                given().when().delete(CONFIGS + "/" + arn);
            }
        }
    }

    @Test
    void putRejectsMalformedAndUnknownConfigurations() {
        String functionName = "signing-negative-" + System.nanoTime();
        String functionPath = LEGACY_FUNCTIONS + "/" + functionName;
        createFunction(functionName);
        try {
            given().contentType("application/json")
                    .body("{\"CodeSigningConfigArn\":\"not-an-arn\"}")
                    .when().put(SIGNING + "/functions/" + functionName + "/code-signing-config")
                    .then().statusCode(400)
                    .body("__type", is("InvalidParameterValueException"));

            given().contentType("application/json")
                    .body("{\"CodeSigningConfigArn\":\""
                            + "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-abcdefghij1234567"
                            + "\"}")
                    .when().put(SIGNING + "/functions/" + functionName + "/code-signing-config")
                    .then().statusCode(404)
                    .body("__type", is("CodeSigningConfigNotFoundException"));
        } finally {
            given().when().delete(functionPath).then().statusCode(204);
        }
    }

    @Test
    void createFunctionAttachesExistingConfiguration() {
        String functionName = "signing-create-" + System.nanoTime();
        String functionPath = LEGACY_FUNCTIONS + "/" + functionName;
        String arn = createConfig();
        try {
            given().contentType("application/json")
                    .body("{\"FunctionName\":\"" + functionName + "\",\"Runtime\":\"nodejs20.x\","
                            + "\"Role\":\"arn:aws:iam::000000000000:role/lambda-role\","
                            + "\"Handler\":\"index.handler\",\"CodeSigningConfigArn\":\"" + arn + "\"}")
                    .when().post(LEGACY_FUNCTIONS)
                    .then().statusCode(201)
                    .body("CodeSigningConfigArn", nullValue());

            given().when().get(SIGNING + "/functions/" + functionName + "/code-signing-config")
                    .then().statusCode(200)
                    .body("CodeSigningConfigArn", is(arn));
            given().when().get(LEGACY_FUNCTIONS + "/" + functionName + "/configuration")
                    .then().statusCode(200)
                    .body("CodeSigningConfigArn", nullValue());
            given().when().get(functionPath)
                    .then().statusCode(200)
                    .body("Configuration.CodeSigningConfigArn", nullValue());
            given().when().get(LEGACY_FUNCTIONS)
                    .then().statusCode(200)
                    .body("Functions.FunctionName", hasItem(functionName))
                    .body("Functions.find { it.FunctionName == '" + functionName
                            + "' }.CodeSigningConfigArn", nullValue());
        } finally {
            given().when().delete(functionPath).then().statusCode(204);
            given().when().delete(CONFIGS + "/" + arn).then().statusCode(204);
        }
    }

    @Test
    void createFunctionRejectsUnknownCodeSigningConfiguration() {
        String functionName = "signing-create-missing-" + System.nanoTime();
        String unknownArn = "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-abcdefghij1234567";

        given().contentType("application/json")
                .body("{\"FunctionName\":\"" + functionName + "\",\"Runtime\":\"nodejs20.x\","
                        + "\"Role\":\"arn:aws:iam::000000000000:role/lambda-role\","
                        + "\"Handler\":\"index.handler\",\"CodeSigningConfigArn\":\"" + unknownArn + "\"}")
                .when().post(LEGACY_FUNCTIONS)
                .then().statusCode(404)
                .body("__type", is("CodeSigningConfigNotFoundException"));
    }

    @Test
    void listFunctionsByCodeSigningConfigPaginatesAttachedFunctions() {
        String suffix = String.valueOf(System.nanoTime());
        String firstFunction = "signing-page-a-" + suffix;
        String secondFunction = "signing-page-b-" + suffix;
        String firstPath = LEGACY_FUNCTIONS + "/" + firstFunction;
        String secondPath = LEGACY_FUNCTIONS + "/" + secondFunction;
        String arn = createConfig();
        try {
            createFunction(firstFunction);
            createFunction(secondFunction);
            attach(firstFunction, arn);
            attach(secondFunction, arn);

            Response firstPage = given().queryParam("MaxItems", 1)
                    .when().get(CONFIGS + "/" + arn + "/functions")
                    .then().statusCode(200)
                    .extract().response();
            String firstArn = firstPage.path("FunctionArns[0]");
            String marker = firstPage.path("NextMarker");
            assertNotNull(firstArn);
            assertNotNull(marker);

            Response secondPage = given().queryParam("MaxItems", 1).queryParam("Marker", marker)
                    .when().get(CONFIGS + "/" + arn + "/functions")
                    .then().statusCode(200)
                    .extract().response();
            String secondArn = secondPage.path("FunctionArns[0]");
            assertNotNull(secondArn);
            assertNotEquals(firstArn, secondArn);
            assertThat(secondPage.path("FunctionArns"), hasSize(1));
            assertThat(secondPage.path("NextMarker"), nullValue());
        } finally {
            try {
                detach(firstFunction);
            } finally {
                try {
                    detach(secondFunction);
                } finally {
                    try {
                        given().when().delete(firstPath);
                    } finally {
                        try {
                            given().when().delete(secondPath);
                        } finally {
                            given().when().delete(CONFIGS + "/" + arn);
                        }
                    }
                }
            }
        }
    }

    private static String createConfig() {
        return given().contentType("application/json")
                .body("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]}}")
                .when().post(CONFIGS)
                .then().statusCode(201)
                .extract().path("CodeSigningConfig.CodeSigningConfigArn");
    }

    private static void createFunction(String functionName) {
        given().contentType("application/json")
                .body("{\"FunctionName\":\"" + functionName + "\",\"Runtime\":\"nodejs20.x\","
                        + "\"Role\":\"arn:aws:iam::000000000000:role/lambda-role\","
                        + "\"Handler\":\"index.handler\"}")
                .when().post(LEGACY_FUNCTIONS)
                .then().statusCode(201);
    }

    private static void attach(String functionName, String arn) {
        given().contentType("application/json")
                .body("{\"CodeSigningConfigArn\":\"" + arn + "\"}")
                .when().put(SIGNING + "/functions/" + functionName + "/code-signing-config")
                .then().statusCode(200);
    }

    private static void detach(String functionName) {
        given().when().delete(SIGNING + "/functions/" + functionName + "/code-signing-config")
                .then().statusCode(204);
    }
}
