package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayAwsStatusSelectionCompatibilityTest {

    @Test
    void sdkConfiguredAwsIntegrationSelectsResponsesByBackendStatus() throws Exception {
        String queueName = TestFixtures.uniqueName("apigw-status-selection");
        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient();
             SqsClient sqs = TestFixtures.sqsClient()) {
            String apiId = apiGateway.createRestApi(request -> request.name(queueName)).id();
            String queueUrl = null;
            try {
                String rootId = apiGateway.getResources(request -> request.restApiId(apiId)).items().stream()
                        .filter(resource -> "/".equals(resource.path())).findFirst().orElseThrow().id();
                String resourceId = apiGateway.createResource(request -> request.restApiId(apiId)
                        .parentId(rootId).pathPart("send")).id();
                apiGateway.putMethod(request -> request.restApiId(apiId).resourceId(resourceId)
                        .httpMethod("POST").authorizationType("NONE"));
                apiGateway.putIntegration(request -> request.restApiId(apiId).resourceId(resourceId)
                        .httpMethod("POST").type(IntegrationType.AWS).integrationHttpMethod("POST")
                        .uri(TestFixtures.arn("apigateway", "sqs", "path/000000000000/" + queueName))
                        .credentials(TestFixtures.globalArn("iam", "000000000000", "role/apigw-sqs"))
                        .requestParameters(Map.of("integration.request.header.Content-Type",
                                "'application/x-www-form-urlencoded'"))
                        .requestTemplates(Map.of("application/json",
                                "Action=SendMessage&MessageBody=$util.urlEncode($input.body)")));
                response(apiGateway, apiId, resourceId, "200", null, "default");
                response(apiGateway, apiId, resourceId, "502", "4\\d{2}", "error");
                apiGateway.createDeployment(request -> request.restApiId(apiId).stageName("test"));
                assertResponse(apiId, 502, "error");

                response(apiGateway, apiId, resourceId, "502", ".*NonExistentQueue.*", "error");
                apiGateway.createDeployment(request -> request.restApiId(apiId).stageName("test"));
                assertResponse(apiId, 200, "default");

                queueUrl = sqs.createQueue(request -> request.queueName(queueName)).queueUrl();
                response(apiGateway, apiId, resourceId, "201", "2\\d{2}", "queued");
                apiGateway.createDeployment(request -> request.restApiId(apiId).stageName("test"));
                assertResponse(apiId, 201, "queued");
                String createdQueueUrl = queueUrl;
                assertThat(sqs.receiveMessage(request -> request.queueUrl(createdQueueUrl)
                        .maxNumberOfMessages(1)).messages())
                        .singleElement().satisfies(message -> assertThat(message.body()).isEqualTo("{\"value\":1}"));
            } finally {
                try {
                    apiGateway.deleteRestApi(request -> request.restApiId(apiId));
                } finally {
                    if (queueUrl != null) {
                        String createdQueueUrl = queueUrl;
                        sqs.deleteQueue(request -> request.queueUrl(createdQueueUrl));
                    }
                }
            }
        }
    }

    private static void response(ApiGatewayClient apiGateway, String apiId, String resourceId,
                                 String status, String pattern, String result) {
        apiGateway.putMethodResponse(request -> request.restApiId(apiId).resourceId(resourceId)
                .httpMethod("POST").statusCode(status));
        apiGateway.putIntegrationResponse(request -> request.restApiId(apiId).resourceId(resourceId)
                .httpMethod("POST").statusCode(status).selectionPattern(pattern)
                .responseTemplates(Map.of("application/json", "{\"result\":\"" + result + "\"}")));
    }

    private static void assertResponse(String apiId, int status, String result) throws Exception {
        URI uri = TestFixtures.endpoint().resolve("/execute-api/" + apiId + "/test/send");
        HttpRequest request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"value\":1}")).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"result\":\"" + result + "\"}");
    }
}
