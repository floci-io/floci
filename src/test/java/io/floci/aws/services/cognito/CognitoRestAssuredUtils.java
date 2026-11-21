package io.floci.aws.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CognitoRestAssuredUtils {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CognitoRestAssuredUtils(){}

    public static Response cognitoAction(String action, String body) {
        return RestAssuredJsonUtils.awsAction("AWSCognitoIdentityProviderService", action, body);
    }

    public static JsonNode cognitoJson(String action, String body) throws Exception {
        return RestAssuredJsonUtils.awsActionJson("AWSCognitoIdentityProviderService", action, body);
    }

    public static String latestSms(String phone) throws Exception {
        JsonNode messages = JSON.readTree(given().queryParam("phone", phone).get("/_aws/sns")
                .then().statusCode(200).extract().asString()).path("messages");
        assertTrue(messages.isArray() && !messages.isEmpty(), "an SMS should have been sent to " + phone);
        return messages.get(messages.size() - 1).path("Message").asText();
    }
}
