package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.BadRequestException;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.apigatewayv2.model.GetIntegrationResponse;
import software.amazon.awssdk.services.apigatewayv2.model.UpdateIntegrationResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ApiGatewayV2SqsSendMessageIntegrationTest {
    private static final String SUBTYPE = "SQS-SendMessage";
    private static final String ROLE = "arn:aws:iam::000000000000:role/apigw-sqs";
    private static final String ATTRIBUTES =
            "{\"Authorization\":{\"DataType\":\"String\",\"StringValue\":\"${request.header.Authorization}\"}}";

    @Inject
    ObjectMapper mapper;

    private ApiGatewayV2Client apigw() {
        return AwsTestClients.create(ApiGatewayV2Client.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    private SqsClient sqs() {
        return AwsTestClients.create(SqsClient.builder(), "test", Region.US_EAST_1);
    }

    private static Map<String, String> parameters(String queueUrl) {
        return Map.of("QueueUrl", queueUrl, "MessageBody", "$request.body", "DelaySeconds", "0",
                "Region", "us-east-1", "MessageAttributes", ATTRIBUTES);
    }

    @Test
    void sdkIntegrationSubtypeSurvivesCreateGetAndUpdate() {
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype").protocolType("HTTP")).apiId();
            try {
                CreateIntegrationResponse created = client.createIntegration(b -> b.apiId(api)
                        .integrationType("AWS_PROXY").integrationSubtype(SUBTYPE).credentialsArn(ROLE)
                        .requestParameters(parameters("http://localhost/000000000000/round-trip")));
                assertEquals(SUBTYPE, created.integrationSubtype());
                assertEquals("1.0", created.payloadFormatVersion());
                assertEquals("$request.body", created.requestParameters().get("MessageBody"));
                String id = created.integrationId();
                GetIntegrationResponse fetched = client.getIntegration(b -> b.apiId(api).integrationId(id));
                assertEquals(SUBTYPE, fetched.integrationSubtype());
                assertEquals(ATTRIBUTES, fetched.requestParameters().get("MessageAttributes"));
                assertEquals(SUBTYPE, client.getIntegrations(b -> b.apiId(api)).items().getFirst()
                        .integrationSubtype());
                UpdateIntegrationResponse updated = client.updateIntegration(b -> b.apiId(api).integrationId(id)
                        .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/other",
                                "MessageBody", "$request.body")));
                assertEquals(SUBTYPE, updated.integrationSubtype());
                assertEquals("http://localhost/000000000000/other", updated.requestParameters().get("QueueUrl"));
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void jsonCompatibilityPathKeepsSubtypeAndParameterNames() throws Exception {
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype-json").protocolType("HTTP")).apiId();
            try {
                JsonNode integration = json("CreateIntegration", Map.of("ApiId", api,
                        "IntegrationType", "AWS_PROXY", "IntegrationSubtype", SUBTYPE,
                        "RequestParameters", parameters("http://localhost/000000000000/json")), 201);
                assertEquals(SUBTYPE, integration.path("IntegrationSubtype").asText());
                assertEquals("$request.body", integration.path("RequestParameters").path("MessageBody").asText());
                assertEquals("0", integration.path("RequestParameters").path("DelaySeconds").asText());
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void createRejectsMissingRequiredParameter() {
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype-missing").protocolType("HTTP")).apiId();
            try {
                BadRequestException missing = assertThrows(BadRequestException.class, () ->
                        client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                                .integrationSubtype(SUBTYPE)
                                .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/q"))));
                assertThat(missing.getMessage(), containsString("MessageBody"));
                BadRequestException unknown = assertThrows(BadRequestException.class, () ->
                        client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                                .integrationSubtype("SQS-PurgeEverything")
                                .requestParameters(parameters("http://localhost/000000000000/q"))));
                assertThat(unknown.getMessage(), containsString("SQS-PurgeEverything"));
                assertThrows(BadRequestException.class, () ->
                        client.createIntegration(b -> b.apiId(api).integrationType("HTTP_PROXY")
                                .integrationUri("https://backend.example/items").integrationMethod("POST")
                                .integrationSubtype(SUBTYPE)
                                .requestParameters(parameters("http://localhost/000000000000/q"))));
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void invokeSendsRequestBodyAndMappedHeaderAttributeToQueue() {
        String queueName = "apigw-sqs-" + UUID.randomUUID();
        try (ApiGatewayV2Client client = apigw(); SqsClient sqs = sqs()) {
            String queueUrl = sqs.createQueue(b -> b.queueName(queueName)).queueUrl();
            String api = client.createApi(b -> b.name("sqs-invoke").protocolType("HTTP")).apiId();
            try {
                String integration = client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                        .integrationSubtype(SUBTYPE).credentialsArn(ROLE)
                        .requestParameters(parameters(queueUrl))).integrationId();
                client.createRoute(b -> b.apiId(api).routeKey("POST /send").target("integrations/" + integration));
                client.createStage(b -> b.apiId(api).stageName("test").autoDeploy(true));

                given().contentType("application/json").header("Authorization", "Bearer token-123")
                        .body("{\"event\":\"created\"}")
                        .post("/execute-api/" + api + "/test/send")
                        .then().statusCode(200)
                        .body("SendMessageResponse.SendMessageResult.MessageId", notNullValue())
                        .body("SendMessageResponse.ResponseMetadata.RequestId", notNullValue());

                List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                        .messageAttributeNames("All").maxNumberOfMessages(10)).messages();
                assertThat(messages, hasSize(1));
                assertThat(messages.getFirst().body(), equalTo("{\"event\":\"created\"}"));
                assertThat(messages.getFirst().messageAttributes().get("Authorization").stringValue(),
                        equalTo("Bearer token-123"));
            } finally {
                client.deleteApi(b -> b.apiId(api));
                sqs.deleteQueue(b -> b.queueUrl(queueUrl));
            }
        }
    }

    @Test
    void invokeWithEmptyBodyReturnsMissingParameter() {
        String queueName = "apigw-sqs-empty-" + UUID.randomUUID();
        try (ApiGatewayV2Client client = apigw(); SqsClient sqs = sqs()) {
            String queueUrl = sqs.createQueue(b -> b.queueName(queueName)).queueUrl();
            String api = client.createApi(b -> b.name("sqs-invoke-empty").protocolType("HTTP")).apiId();
            try {
                String integration = client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                        .integrationSubtype(SUBTYPE).requestParameters(parameters(queueUrl))).integrationId();
                client.createRoute(b -> b.apiId(api).routeKey("POST /send").target("integrations/" + integration));
                client.createStage(b -> b.apiId(api).stageName("test").autoDeploy(true));

                given().post("/execute-api/" + api + "/test/send")
                        .then().statusCode(400)
                        .body("Error.Code", equalTo("MissingParameter"));
                assertThat(sqs.receiveMessage(b -> b.queueUrl(queueUrl)).messages(), hasSize(0));
            } finally {
                client.deleteApi(b -> b.apiId(api));
                sqs.deleteQueue(b -> b.queueUrl(queueUrl));
            }
        }
    }

    private JsonNode json(String action, Map<String, Object> body, int status) throws Exception {
        String response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonApiGatewayV2." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/apigatewayv2/aws4_request")
                .body(mapper.writeValueAsBytes(body)).post("/").then().statusCode(status).extract().asString();
        return mapper.readTree(response);
    }
}
