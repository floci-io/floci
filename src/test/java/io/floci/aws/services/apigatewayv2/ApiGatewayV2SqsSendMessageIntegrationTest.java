package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.BadRequestException;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.apigatewayv2.model.GetIntegrationResponse;
import software.amazon.awssdk.services.apigatewayv2.model.Route;
import software.amazon.awssdk.services.apigatewayv2.model.UpdateIntegrationResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void updateMergesRequestParametersAndEmptyValueRemovesKey() {
        String queueUrl = "http://localhost/000000000000/merge";
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype-merge").protocolType("HTTP")).apiId();
            try {
                String id = client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                        .integrationSubtype(SUBTYPE).requestParameters(parameters(queueUrl))).integrationId();

                Map<String, String> delayed = client.updateIntegration(b -> b.apiId(api).integrationId(id)
                        .requestParameters(Map.of("DelaySeconds", "5"))).requestParameters();
                assertEquals("5", delayed.get("DelaySeconds"));
                assertEquals(queueUrl, delayed.get("QueueUrl"));
                assertEquals("$request.body", delayed.get("MessageBody"));
                assertEquals(ATTRIBUTES, delayed.get("MessageAttributes"));

                Map<String, String> removed = client.updateIntegration(b -> b.apiId(api).integrationId(id)
                        .requestParameters(Map.of("MessageAttributes", ""))).requestParameters();
                assertFalse(removed.containsKey("MessageAttributes"));
                assertEquals(queueUrl, removed.get("QueueUrl"));
                assertEquals("5", removed.get("DelaySeconds"));

                BadRequestException missing = assertThrows(BadRequestException.class, () ->
                        client.updateIntegration(b -> b.apiId(api).integrationId(id)
                                .requestParameters(Map.of("QueueUrl", ""))));
                assertThat(missing.getMessage(), containsString("QueueUrl"));
                assertEquals(queueUrl, client.getIntegration(b -> b.apiId(api).integrationId(id))
                        .requestParameters().get("QueueUrl"));
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

                JsonNode numeric = json("CreateIntegration", Map.of("ApiId", api,
                        "IntegrationType", "AWS_PROXY", "IntegrationSubtype", SUBTYPE,
                        "RequestParameters", Map.of("QueueUrl", "http://localhost/000000000000/json",
                                "MessageBody", "$request.body", "DelaySeconds", 5)), 201);
                assertEquals("5", numeric.path("RequestParameters").path("DelaySeconds").asText());

                JsonNode nested = json("CreateIntegration", Map.of("ApiId", api,
                        "IntegrationType", "AWS_PROXY", "IntegrationSubtype", SUBTYPE,
                        "RequestParameters", Map.of("QueueUrl", "http://localhost/000000000000/json",
                                "MessageBody", Map.of("nested", "value"))), 400);
                assertEquals("BadRequestException", nested.path("__type").asText());

                Map<String, Object> nullQueue = new HashMap<>();
                nullQueue.put("QueueUrl", null);
                nullQueue.put("MessageBody", "$request.body");
                JsonNode absent = json("CreateIntegration", Map.of("ApiId", api,
                        "IntegrationType", "AWS_PROXY", "IntegrationSubtype", SUBTYPE,
                        "RequestParameters", nullQueue), 400);
                assertEquals("BadRequestException", absent.path("__type").asText());
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
                BadRequestException empty = assertThrows(BadRequestException.class, () ->
                        client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                                .integrationSubtype(SUBTYPE)
                                .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/q",
                                        "MessageBody", ""))));
                assertThat(empty.getMessage(), containsString("MessageBody"));
                assertEquals("  ", client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                        .integrationSubtype(SUBTYPE)
                        .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/q",
                                "MessageBody", "  "))).requestParameters().get("MessageBody"));
                BadRequestException malformed = assertThrows(BadRequestException.class, () ->
                        client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                                .integrationSubtype(SUBTYPE)
                                .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/q",
                                        "MessageBody", "$request.body", "MessageAttributes", "{not json"))));
                assertThat(malformed.getMessage(), containsString("MessageAttributes"));
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
    void restPathReadsScalarParametersAsTextAndRejectsContainers() {
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype-rest").protocolType("HTTP")).apiId();
            try {
                given().contentType(ContentType.JSON)
                        .body("""
                                {"integrationType":"AWS_PROXY","integrationSubtype":"SQS-SendMessage",
                                 "requestParameters":{"QueueUrl":"http://localhost/000000000000/rest",
                                 "MessageBody":"$request.body","DelaySeconds":5}}
                                """)
                        .when().post("/v2/apis/" + api + "/integrations")
                        .then().statusCode(201)
                        .body("requestParameters.DelaySeconds", equalTo("5"));
                given().contentType(ContentType.JSON)
                        .body("""
                                {"integrationType":"AWS_PROXY","integrationSubtype":"SQS-SendMessage",
                                 "requestParameters":{"QueueUrl":"http://localhost/000000000000/rest",
                                 "MessageBody":{"nested":"value"}}}
                                """)
                        .when().post("/v2/apis/" + api + "/integrations")
                        .then().statusCode(400);
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void createRejectsUnsupportedSourceExpressions() {
        String queueUrl = "http://localhost/000000000000/expressions";
        List<Map.Entry<String, Map<String, String>>> rejected = List.of(
                Map.entry("$5 off", Map.of("QueueUrl", queueUrl, "MessageBody", "$5 off")),
                Map.entry("$request.heder.x", Map.of("QueueUrl", queueUrl, "MessageBody", "$request.heder.x")),
                Map.entry("$request.heder.x", Map.of("QueueUrl", queueUrl, "MessageBody", "id ${request.heder.x}")),
                Map.entry("$request.heder.Authorization", Map.of("QueueUrl", queueUrl,
                        "MessageBody", "$request.body", "MessageAttributes", ATTRIBUTES.replace("header", "heder"))));
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype-expressions").protocolType("HTTP")).apiId();
            try {
                for (Map.Entry<String, Map<String, String>> entry : rejected) {
                    BadRequestException error = assertThrows(BadRequestException.class, () ->
                            client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                                    .integrationSubtype(SUBTYPE).requestParameters(entry.getValue())));
                    assertThat(error.getMessage(), containsString(entry.getKey()));
                }
                assertThrows(BadRequestException.class, () ->
                        client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                                .integrationSubtype(SUBTYPE)
                                .requestParameters(Map.of("QueueUrl", queueUrl, "MessageBody", "$request.body",
                                        "MessageAttributes", ATTRIBUTES + " {}"))));
                CreateIntegrationResponse mixed = client.createIntegration(b -> b.apiId(api)
                        .integrationType("AWS_PROXY").integrationSubtype(SUBTYPE)
                        .requestParameters(Map.of("QueueUrl", queueUrl,
                                "MessageBody", "price $5 for ${request.header.X-Item} on ${context.stage}")));
                assertEquals(SUBTYPE, mixed.integrationSubtype());
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void restPathTreatsNullPayloadFormatVersionAsAbsent() {
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype-null-version").protocolType("HTTP")).apiId();
            try {
                String id = given().contentType(ContentType.JSON)
                        .body("""
                                {"integrationType":"AWS_PROXY","integrationSubtype":"SQS-SendMessage",
                                 "payloadFormatVersion":null,
                                 "requestParameters":{"QueueUrl":"http://localhost/000000000000/rest",
                                 "MessageBody":"$request.body"}}
                                """)
                        .when().post("/v2/apis/" + api + "/integrations")
                        .then().statusCode(201)
                        .body("payloadFormatVersion", equalTo("1.0"))
                        .extract().path("integrationId");
                given().contentType(ContentType.JSON)
                        .body("{\"payloadFormatVersion\":null}")
                        .when().patch("/v2/apis/" + api + "/integrations/" + id)
                        .then().statusCode(200)
                        .body("payloadFormatVersion", equalTo("1.0"));
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void importReadsObjectValuedParameterAsJson() throws Exception {
        String document = """
                {"openapi": "3.0.1", "info": {"title": "subtype-object-parameter", "version": "1.0"},
                 "paths": {"/send": {"post": {"x-amazon-apigateway-integration": {
                   "type": "aws_proxy", "integrationSubtype": "SQS-SendMessage",
                   "payloadFormatVersion": "1.0", "credentials": "%s",
                   "requestParameters": {"QueueUrl": "http://localhost/000000000000/q",
                     "MessageBody": "$request.body",
                     "MessageAttributes": {"Authorization": {"DataType": "String",
                       "StringValue": "${request.header.Authorization}"}}}}}}}}
                """.formatted(ROLE);
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.importApi(b -> b.body(document)).apiId();
            try {
                String attributes = client.getIntegrations(b -> b.apiId(api)).items().getFirst()
                        .requestParameters().get("MessageAttributes");
                assertEquals(mapper.readTree(ATTRIBUTES), mapper.readTree(attributes));
                assertThrows(BadRequestException.class, () -> client.reimportApi(b -> b.apiId(api)
                        .body(document.replace("\"MessageAttributes\": {", "\"MessageAttributes\": [{")
                                .replace("}}}}}}}}", "}}]}}}}}}"))));
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void updateThatAddsSubtypeDefaultsPayloadFormatVersion() {
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-subtype-add").protocolType("HTTP")).apiId();
            try {
                String id = client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                        .integrationUri("arn:aws:lambda:us-east-1:000000000000:function:target")).integrationId();
                UpdateIntegrationResponse updated = client.updateIntegration(b -> b.apiId(api).integrationId(id)
                        .integrationSubtype(SUBTYPE)
                        .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/added",
                                "MessageBody", "$request.body")));
                assertEquals(SUBTYPE, updated.integrationSubtype());
                assertEquals("1.0", updated.payloadFormatVersion());
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void subtypeWithoutInvokerIsStoredAndAnswersServerError() {
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.createApi(b -> b.name("sqs-purge").protocolType("HTTP")).apiId();
            try {
                CreateIntegrationResponse created = client.createIntegration(b -> b.apiId(api)
                        .integrationType("AWS_PROXY").integrationSubtype("SQS-PurgeQueue")
                        .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/purge")));
                assertEquals("SQS-PurgeQueue", created.integrationSubtype());
                assertThrows(BadRequestException.class, () ->
                        client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                                .integrationSubtype("SQS-PurgeQueue")
                                .requestParameters(Map.of("QueueUrl", "http://localhost/000000000000/purge",
                                        "MessageBody", "$request.body"))));
                client.createRoute(b -> b.apiId(api).routeKey("POST /purge")
                        .target("integrations/" + created.integrationId()));
                client.createStage(b -> b.apiId(api).stageName("test").autoDeploy(true));

                given().post("/execute-api/" + api + "/test/purge")
                        .then().statusCode(500);
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void reimportWithInvalidSubtypeLeavesApiUnchanged() {
        String valid = """
                {"openapi": "3.0.1", "info": {"title": "subtype-reimport", "version": "1.0"},
                 "paths": {"/items": {"get": {"x-amazon-apigateway-integration": {
                   "type": "http_proxy", "httpMethod": "GET", "uri": "https://backend.example/items",
                   "payloadFormatVersion": "1.0"}}}}}
                """;
        String invalid = """
                {"openapi": "3.0.1", "info": {"title": "subtype-reimport", "version": "1.0"},
                 "paths": {"/send": {"post": {"x-amazon-apigateway-integration": {
                   "type": "aws_proxy", "integrationSubtype": "SQS-PurgeEverything",
                   "payloadFormatVersion": "1.0",
                   "requestParameters": {"QueueUrl": "http://localhost/000000000000/q"}}}}}}
                """;
        try (ApiGatewayV2Client client = apigw()) {
            String api = client.importApi(b -> b.body(valid)).apiId();
            try {
                assertThrows(BadRequestException.class, () -> client.reimportApi(b -> b.apiId(api).body(invalid)));
                List<String> routeKeys = client.getRoutes(b -> b.apiId(api)).items().stream()
                        .map(Route::routeKey).toList();
                assertEquals(List.of("GET /items"), routeKeys);
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void invokeSendsRequestBodyAndMappedHeaderAttributeToQueue() {
        withSendMessageRoute("sqs-invoke", ApiGatewayV2SqsSendMessageIntegrationTest::parameters,
                (sqs, queueUrl, api) -> {
                    given().contentType("application/json").header("Authorization", "Bearer token-123")
                            .body("{\"event\":\"created\"}")
                            .post("/execute-api/" + api + "/test/send")
                            .then().statusCode(200)
                            .body("SendMessageResponse.SendMessageResult.MessageId", notNullValue())
                            .body("SendMessageResponse.ResponseMetadata.RequestId", notNullValue());

                    Message message = onlyMessage(sqs, queueUrl);
                    assertThat(message.body(), equalTo("{\"event\":\"created\"}"));
                    assertThat(message.messageAttributes().get("Authorization").stringValue(),
                            equalTo("Bearer token-123"));
                });
    }

    @Test
    void invokeWithEmptyBodyReturnsMissingParameter() {
        withSendMessageRoute("sqs-invoke-empty", ApiGatewayV2SqsSendMessageIntegrationTest::parameters,
                (sqs, queueUrl, api) -> {
                    given().post("/execute-api/" + api + "/test/send")
                            .then().statusCode(400)
                            .body("Error.Code", equalTo("MissingParameter"));
                    assertThat(sqs.receiveMessage(b -> b.queueUrl(queueUrl)).messages(), hasSize(0));
                });
    }

    @Test
    void invokeSendsWhitespaceOnlyBody() {
        withSendMessageRoute("sqs-invoke-blank", ApiGatewayV2SqsSendMessageIntegrationTest::parameters,
                (sqs, queueUrl, api) -> {
                    given().contentType("text/plain").header("Authorization", "Bearer token-123").body("   ")
                            .post("/execute-api/" + api + "/test/send")
                            .then().statusCode(200);
                    assertThat(onlyMessage(sqs, queueUrl).body(), equalTo("   "));
                });
    }

    @Test
    void invokeSendsBodyFieldSelectedByPath() {
        withSendMessageRoute("sqs-invoke-path",
                queueUrl -> Map.of("QueueUrl", queueUrl, "MessageBody", "$request.body.message"),
                (sqs, queueUrl, api) -> {
                    given().contentType("application/json").body("{\"message\":\"hi\"}")
                            .post("/execute-api/" + api + "/test/send")
                            .then().statusCode(200);
                    assertThat(onlyMessage(sqs, queueUrl).body(), equalTo("hi"));
                });
    }

    @Test
    void invokeKeepsHeaderValueInsideItsAttribute() {
        String header = "x\"},\"TenantId\":{\"DataType\":\"String\",\"StringValue\":\"evil";
        withSendMessageRoute("sqs-invoke-quoted", ApiGatewayV2SqsSendMessageIntegrationTest::parameters,
                (sqs, queueUrl, api) -> {
                    given().contentType("application/json").header("Authorization", header).body("{}")
                            .post("/execute-api/" + api + "/test/send")
                            .then().statusCode(200);
                    Message message = onlyMessage(sqs, queueUrl);
                    assertThat(message.messageAttributes().get("Authorization").stringValue(), equalTo(header));
                    assertFalse(message.messageAttributes().containsKey("TenantId"));
                });
    }

    @Test
    void invokeResolvesContextVariables() {
        String attributes = "{\"Stage\":{\"DataType\":\"String\",\"StringValue\":\"${context.stage}\"},"
                + "\"Route\":{\"DataType\":\"String\",\"StringValue\":\"${context.routeKey}\"},"
                + "\"Path\":{\"DataType\":\"String\",\"StringValue\":\"${context.path}\"}}";
        withSendMessageRoute("sqs-invoke-context",
                queueUrl -> Map.of("QueueUrl", queueUrl, "MessageBody", "$context.httpMethod",
                        "MessageAttributes", attributes),
                (sqs, queueUrl, api) -> {
                    given().post("/execute-api/" + api + "/test/send").then().statusCode(200);
                    Message message = onlyMessage(sqs, queueUrl);
                    assertThat(message.body(), equalTo("POST"));
                    assertThat(message.messageAttributes().get("Stage").stringValue(), equalTo("test"));
                    assertThat(message.messageAttributes().get("Route").stringValue(), equalTo("POST /send"));
                    assertThat(message.messageAttributes().get("Path").stringValue(), equalTo("/test/send"));
                });
    }

    private interface RouteCheck {
        void run(SqsClient sqs, String queueUrl, String apiId);
    }

    /**
     * Creates a queue and an HTTP API whose {@code POST /send} route on stage {@code test} sends to it
     * through an SQS-SendMessage integration, runs the check, then deletes both.
     */
    private void withSendMessageRoute(String name, Function<String, Map<String, String>> parameters,
                                      RouteCheck check) {
        try (ApiGatewayV2Client client = apigw(); SqsClient sqs = sqs()) {
            String api = client.createApi(b -> b.name(name).protocolType("HTTP")).apiId();
            String queueUrl = null;
            try {
                String createdQueue = sqs.createQueue(b -> b.queueName(name + "-" + UUID.randomUUID())).queueUrl();
                queueUrl = createdQueue;
                String integration = client.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                        .integrationSubtype(SUBTYPE).credentialsArn(ROLE)
                        .requestParameters(parameters.apply(createdQueue))).integrationId();
                client.createRoute(b -> b.apiId(api).routeKey("POST /send").target("integrations/" + integration));
                client.createStage(b -> b.apiId(api).stageName("test").autoDeploy(true));
                check.run(sqs, createdQueue, api);
            } finally {
                client.deleteApi(b -> b.apiId(api));
                if (queueUrl != null) {
                    String createdQueue = queueUrl;
                    sqs.deleteQueue(b -> b.queueUrl(createdQueue));
                }
            }
        }
    }

    private static Message onlyMessage(SqsClient sqs, String queueUrl) {
        List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                .messageAttributeNames("All").maxNumberOfMessages(10)).messages();
        assertThat(messages, hasSize(1));
        return messages.getFirst();
    }

    private JsonNode json(String action, Map<String, Object> body, int status) throws Exception {
        String response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonApiGatewayV2." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/apigatewayv2/aws4_request")
                .body(mapper.writeValueAsBytes(body)).post("/").then().statusCode(status).extract().asString();
        return mapper.readTree(response);
    }
}
