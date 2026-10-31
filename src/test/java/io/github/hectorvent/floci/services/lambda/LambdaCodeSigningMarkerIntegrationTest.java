package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.Base64Url;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LambdaCodeSigningMarkerIntegrationTest {
    private static final String ACCOUNT = "741681400003";
    private static final String REGION = "eu-west-2";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261028/" + REGION + "/lambda/aws4_request";
    private static final String BASE = "/2020-04-22/code-signing-configs";

    @Test
    void unsignedCursorCannotBeUsedAsAMarker() {
        String marker = Base64Url.encode("ListCodeSigningConfigs:" + ACCOUNT + ":" + REGION
                + ":csc-aaaaaaaaaaaaaaaaa");
        given().header("Authorization", AUTH).queryParam("Marker", marker).get(BASE)
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid pagination key."));
    }

    @Test
    void emptyMarkerUsesTheLambdaPaginationError() {
        given().header("Authorization", AUTH).queryParam("Marker", "").get(BASE)
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid pagination key."));
    }
}
