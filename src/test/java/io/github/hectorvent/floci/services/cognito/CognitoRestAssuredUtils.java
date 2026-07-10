package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.restassured.response.Response;

public class CognitoRestAssuredUtils {
    private CognitoRestAssuredUtils(){}

    public static Response cognitoAction(String action, String body) {
        return RestAssuredJsonUtils.awsAction("AWSCognitoIdentityProviderService", action, body);
    }

    public static JsonNode cognitoJson(String action, String body) throws Exception {
        return RestAssuredJsonUtils.awsActionJson("AWSCognitoIdentityProviderService", action, body);
    }
}
