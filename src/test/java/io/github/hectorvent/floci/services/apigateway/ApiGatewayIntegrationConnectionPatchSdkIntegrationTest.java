package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ConnectionType;
import software.amazon.awssdk.services.apigateway.model.GetIntegrationResponse;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigateway.model.UpdateIntegrationResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ApiGatewayIntegrationConnectionPatchSdkIntegrationTest {

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    @TestHTTPResource("/")
    URI endpoint;

    private ApiGatewayClient client() {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(Apache5HttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    void sdkUpdatesVpcLinkConnectionAndReadsItBack() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("integration-connection-patch-sdk")).id();
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                String resourceId = client.createResource(request -> request.restApiId(apiId)
                        .parentId(rootId).pathPart("widget")).id();
                client.putMethod(request -> request.restApiId(apiId).resourceId(resourceId)
                        .httpMethod("POST").authorizationType("NONE"));
                client.putIntegration(request -> request.restApiId(apiId).resourceId(resourceId)
                        .httpMethod("POST").type(IntegrationType.HTTP_PROXY).integrationHttpMethod("POST")
                        .uri("http://example.invalid/widget"));

                String linkId = client.createVpcLink(request -> request.name("integration-patch-sdk-link")
                        .targetArns("arn:aws:elasticloadbalancing:us-east-1:000000000000:"
                                + "loadbalancer/net/test/abc")).id();
                try {
                    UpdateIntegrationResponse updated = client.updateIntegration(request -> request
                            .restApiId(apiId).resourceId(resourceId).httpMethod("POST")
                            .patchOperations(
                                    PatchOperation.builder().op("replace").path("/connectionType")
                                            .value("VPC_LINK").build(),
                                    PatchOperation.builder().op("replace").path("/connectionId")
                                            .value(linkId).build()));
                    assertEquals(ConnectionType.VPC_LINK, updated.connectionType());
                    assertEquals(linkId, updated.connectionId());

                    GetIntegrationResponse read = client.getIntegration(request -> request
                            .restApiId(apiId).resourceId(resourceId).httpMethod("POST"));
                    assertEquals(ConnectionType.VPC_LINK, read.connectionType());
                    assertEquals(linkId, read.connectionId());

                    UpdateIntegrationResponse internet = client.updateIntegration(request -> request
                            .restApiId(apiId).resourceId(resourceId).httpMethod("POST")
                            .patchOperations(PatchOperation.builder().op("replace")
                                    .path("/connectionType").value("INTERNET").build()));
                    assertEquals(ConnectionType.INTERNET, internet.connectionType());
                    assertNull(internet.connectionId());
                    assertNull(client.getIntegration(request -> request.restApiId(apiId)
                            .resourceId(resourceId).httpMethod("POST")).connectionId());
                } finally {
                    client.deleteVpcLink(request -> request.vpcLinkId(linkId));
                }

                String v2LinkId = apiGatewayV2Service.createVpcLink(Region.US_EAST_1.id(), Map.of(
                        "name", "integration-patch-sdk-v2-link", "subnetIds", List.of("subnet-test"),
                        "securityGroupIds", List.of("sg-test"))).getVpcLinkId();
                try {
                    UpdateIntegrationResponse v2Updated = client.updateIntegration(request -> request
                            .restApiId(apiId).resourceId(resourceId).httpMethod("POST")
                            .patchOperations(
                                    PatchOperation.builder().op("replace").path("/connectionType")
                                            .value("VPC_LINK").build(),
                                    PatchOperation.builder().op("replace").path("/connectionId")
                                            .value(v2LinkId).build()));
                    assertEquals(ConnectionType.VPC_LINK, v2Updated.connectionType());
                    assertEquals(v2LinkId, v2Updated.connectionId());
                    assertEquals(v2LinkId, client.getIntegration(request -> request
                            .restApiId(apiId).resourceId(resourceId).httpMethod("POST")).connectionId());
                } finally {
                    apiGatewayV2Service.deleteVpcLink(Region.US_EAST_1.id(), v2LinkId);
                }
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
