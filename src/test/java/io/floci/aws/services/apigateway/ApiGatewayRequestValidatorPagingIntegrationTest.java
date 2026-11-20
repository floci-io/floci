package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetRequestValidatorsResponse;
import software.amazon.awssdk.services.apigateway.model.RequestValidator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ApiGatewayRequestValidatorPagingIntegrationTest {

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    @Test
    void getRequestValidatorsWithLimitFollowsPositionToTerminalPage() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("validator-paging-sdk")).id();
            try {
                Set<String> created = new HashSet<>();
                for (int i = 0; i < 3; i++) {
                    String name = "Validator" + i;
                    boolean body = i != 0;
                    created.add(client.createRequestValidator(request -> request.restApiId(apiId).name(name)
                            .validateRequestBody(body).validateRequestParameters(!body)).id());
                }

                List<String> seen = ApiGatewayPagingWalk.oneItemPerPageIds(
                        position -> client.getRequestValidators(
                                request -> request.restApiId(apiId).limit(1).position(position)),
                        GetRequestValidatorsResponse::items, GetRequestValidatorsResponse::position,
                        RequestValidator::id);
                assertEquals(created, new HashSet<>(seen));

                GetRequestValidatorsResponse all = client.getRequestValidators(request -> request.restApiId(apiId));
                assertEquals(3, all.items().size());
                assertNull(all.position());

                assertThrows(BadRequestException.class, () -> client.getRequestValidators(
                        request -> request.restApiId(apiId).limit(1).position("missing")));
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
