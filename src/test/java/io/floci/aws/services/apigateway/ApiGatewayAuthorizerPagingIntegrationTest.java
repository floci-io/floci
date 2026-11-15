package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.Authorizer;
import software.amazon.awssdk.services.apigateway.model.AuthorizerType;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetAuthorizersResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ApiGatewayAuthorizerPagingIntegrationTest {

    private static final String AUTHORIZER_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:page-authorizer/invocations";

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    private Set<String> createAuthorizers(ApiGatewayClient client, String apiId, int count) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String name = "page-authorizer-" + i;
            ids.add(client.createAuthorizer(request -> request.restApiId(apiId).name(name)
                    .type(AuthorizerType.TOKEN).authorizerUri(AUTHORIZER_URI)
                    .identitySource("method.request.header.Authorization")).id());
        }
        return ids;
    }

    @Test
    void getAuthorizersWithLimitReturnsPositionAndFollowsToTerminalPage() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("authorizer-paging-sdk")).id();
            try {
                Set<String> created = createAuthorizers(client, apiId, 3);

                GetAuthorizersResponse first = client.getAuthorizers(request -> request.restApiId(apiId).limit(1));
                assertEquals(1, first.items().size());
                assertNotNull(first.position());

                List<String> seen = new ArrayList<>();
                List<String> cursors = new ArrayList<>();
                seen.add(first.items().get(0).id());
                String position = first.position();
                for (int page = 0; position != null && page < created.size(); page++) {
                    assertFalse(cursors.contains(position), "position repeated: " + position);
                    cursors.add(position);
                    String next = position;
                    GetAuthorizersResponse response = client.getAuthorizers(request -> request.restApiId(apiId)
                            .limit(1).position(next));
                    assertEquals(1, response.items().size());
                    assertFalse(seen.contains(response.items().get(0).id()), "authorizer repeated");
                    seen.add(response.items().get(0).id());
                    position = response.position();
                }
                assertNull(position);
                assertEquals(3, seen.size());
                assertEquals(created, new HashSet<>(seen));
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void getAuthorizersDefaultsToTwentyFiveAndKeepsAuthorizerFields() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("authorizer-default-page-sdk")).id();
            try {
                createAuthorizers(client, apiId, 27);

                GetAuthorizersResponse first = client.getAuthorizers(request -> request.restApiId(apiId));
                assertEquals(25, first.items().size());
                assertNotNull(first.position());
                Authorizer sample = first.items().get(0);
                assertEquals(AuthorizerType.TOKEN, sample.type());
                assertEquals(AUTHORIZER_URI, sample.authorizerUri());
                assertEquals("method.request.header.Authorization", sample.identitySource());

                GetAuthorizersResponse second = client.getAuthorizers(request -> request.restApiId(apiId)
                        .position(first.position()));
                assertEquals(2, second.items().size());
                assertNull(second.position());
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void getAuthorizersIsolatesParentsAndRejectsBadInput() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("authorizer-paging-a")).id();
            String otherId = client.createRestApi(request -> request.name("authorizer-paging-b")).id();
            try {
                createAuthorizers(client, apiId, 2);

                GetAuthorizersResponse other = client.getAuthorizers(request -> request.restApiId(otherId));
                assertEquals(0, other.items().size());
                assertNull(other.position());

                GetAuthorizersResponse all = client.getAuthorizers(request -> request.restApiId(apiId).limit(2));
                assertEquals(2, all.items().size());
                assertNull(all.position());

                assertThrows(BadRequestException.class,
                        () -> client.getAuthorizers(request -> request.restApiId(apiId).limit(0)));
                assertThrows(BadRequestException.class,
                        () -> client.getAuthorizers(request -> request.restApiId(apiId).position("missing")));
                assertThrows(NotFoundException.class,
                        () -> client.getAuthorizers(request -> request.restApiId("missingapi")));
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
                client.deleteRestApi(request -> request.restApiId(otherId));
            }
        }
    }
}
