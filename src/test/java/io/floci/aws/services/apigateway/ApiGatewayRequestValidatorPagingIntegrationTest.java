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

                GetRequestValidatorsResponse page = client.getRequestValidators(
                        request -> request.restApiId(apiId).limit(1));
                List<String> seen = new ArrayList<>();
                while (true) {
                    assertEquals(1, page.items().size());
                    RequestValidator validator = page.items().get(0);
                    assertFalse(seen.contains(validator.id()), "validator repeated");
                    assertNotNull(validator.name());
                    seen.add(validator.id());
                    if (page.position() == null) {
                        break;
                    }
                    String next = page.position();
                    page = client.getRequestValidators(request -> request.restApiId(apiId).limit(1).position(next));
                }
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
