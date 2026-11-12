package io.floci.aws.services.apigateway;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ApiGatewayModelTemplateIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scopedSdkReadAndExplicitDenyCoverTheActualTemplateRoute(boolean websocket) {
        String prefix = websocket ? "/v2/apis/" : "/restapis/";
        String api = given().header("Authorization", auth("test", "apigateway"))
                .contentType("application/json")
                .body(websocket ? Map.of("name", "template-iam", "protocolType", "WEBSOCKET",
                        "routeSelectionExpression", "$request.body.action") : Map.of("name", "template-iam"))
                .post(prefix.substring(0, prefix.length() - 1)).then().statusCode(201)
                .extract().path(websocket ? "apiId" : "id");
        cleanup.register(() -> given().port(endpoint.getPort()).header("Authorization", auth("test", "apigateway"))
                .delete(prefix + api));
        String model = given().header("Authorization", auth("test", "apigateway"))
                .contentType("application/json")
                .body(Map.of("name", "IamModel", "contentType", "application/json", "schema", "{\"type\":\"string\"}"))
                .post(prefix + api + "/models").then().statusCode(201).extract().path(websocket ? "modelId" : "name");
        String path = prefix + api + "/models/" + model + (websocket ? "/template" : "/default_template");
        String resource = "arn:aws:apigateway:us-east-1::" + (websocket ? path.substring(3) : path);
        String user = "template-reader-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth("test", "iam"), user).accessKeyId();
        policy(user, statement("Allow", "apigateway:GET", resource));
        assertFalse(read(websocket, key, api, model).isBlank());
        policy(user, statement("Allow", "apigateway:GET", resource + "-wrong"));
        assertEquals(403, assertThrows(AwsServiceException.class, () -> read(websocket, key, api, model)).statusCode());
        // Explicitly allow the spoofed action so this checks route attribution, not a blanket refusal.
        policy(user, statement("Allow", "apigateway:*", "*") + "," + statement("Deny", "apigateway:GET", resource));
        assertEquals(403, assertThrows(AwsServiceException.class, () -> read(websocket, key, api, model)).statusCode());
        given().header("Authorization", auth(key, "apigateway"))
                .header("X-Amz-Target", "AmazonApiGatewayV2.CreateApi")
                .get(path + "?Action=CreateApi").then().statusCode(403).body(containsString("apigateway:GET"));
    }

    private String read(boolean websocket, String key, String api, String model) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(key, "test"));
        if (websocket) {
            try (ApiGatewayV2Client client = ApiGatewayV2Client.builder().endpointOverride(endpoint)
                    .region(Region.US_EAST_1).credentialsProvider(credentials)
                    .httpClientBuilder(Apache5HttpClient.builder()).build()) {
                return client.getModelTemplate(request -> request.apiId(api).modelId(model)).value();
            }
        }
        try (ApiGatewayClient client = ApiGatewayClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials)
                .httpClientBuilder(Apache5HttpClient.builder()).build()) {
            return client.getModelTemplate(request -> request.restApiId(api).modelName(model)).value();
        }
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("test", "iam"), user, "template-read",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }
}
