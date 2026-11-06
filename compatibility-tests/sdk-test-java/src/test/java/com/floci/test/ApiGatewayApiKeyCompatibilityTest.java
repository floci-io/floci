package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiKey;
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.GetApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.GetApiKeysResponse;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigateway.model.UpdateApiKeyResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API Gateway API keys")
class ApiGatewayApiKeyCompatibilityTest {

    @Test
    @DisplayName("API key responses expose the complete AWS SDK shape")
    void apiKeyResponsesExposeCompleteShape() {
        ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient();
        String value = "11112222333344445555666677778888";
        CreateApiKeyResponse created = apiGateway.createApiKey(request -> request
                .name(TestFixtures.uniqueName("apigw-key"))
                .enabled(true)
                .customerId("marketplace-customer-1")
                .value(value));

        try {
            assertThat(created.id()).isNotBlank().isNotEqualTo(value);
            assertThat(created.value()).isEqualTo(value);
            assertThat(created.customerId()).isEqualTo("marketplace-customer-1");
            assertCompleteShape(created.createdDate(), created.lastUpdatedDate(),
                    created.hasStageKeys(), created.stageKeys(), created.hasTags(), created.tags());

            GetApiKeyResponse read = apiGateway.getApiKey(request -> request.apiKey(created.id()));
            assertThat(read.value()).isNull();
            assertThat(read.customerId()).isEqualTo("marketplace-customer-1");
            assertCompleteShape(read.createdDate(), read.lastUpdatedDate(),
                    read.hasStageKeys(), read.stageKeys(), read.hasTags(), read.tags());

            GetApiKeyResponse readWithValue = apiGateway.getApiKey(request -> request
                    .apiKey(created.id())
                    .includeValue(true));
            assertThat(readWithValue.value()).isEqualTo(value);

            GetApiKeysResponse listed = apiGateway.getApiKeys();
            ApiKey listedKey = listed.items().stream()
                    .filter(key -> created.id().equals(key.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(listedKey.value()).isNull();
            assertThat(listedKey.customerId()).isEqualTo("marketplace-customer-1");
            assertCompleteShape(listedKey.createdDate(), listedKey.lastUpdatedDate(),
                    listedKey.hasStageKeys(), listedKey.stageKeys(), listedKey.hasTags(), listedKey.tags());

            UpdateApiKeyResponse updated = apiGateway.updateApiKey(request -> request
                    .apiKey(created.id())
                    .patchOperations(PatchOperation.builder()
                            .op("replace")
                            .path("/customerId")
                            .value("marketplace-customer-2")
                            .build()));
            assertThat(updated.value()).isNull();
            assertThat(updated.customerId()).isEqualTo("marketplace-customer-2");
            assertCompleteShape(updated.createdDate(), updated.lastUpdatedDate(),
                    updated.hasStageKeys(), updated.stageKeys(), updated.hasTags(), updated.tags());
        } finally {
            apiGateway.deleteApiKey(request -> request.apiKey(created.id()));
            apiGateway.close();
        }
    }

    private static void assertCompleteShape(Instant createdDate,
                                            Instant lastUpdatedDate,
                                            boolean hasStageKeys,
                                            List<String> stageKeys,
                                            boolean hasTags,
                                            Map<String, String> tags) {
        assertThat(createdDate).isNotNull();
        assertThat(lastUpdatedDate).isNotNull();
        assertThat(hasStageKeys).isTrue();
        assertThat(stageKeys).isEmpty();
        assertThat(hasTags).isTrue();
        assertThat(tags).isEmpty();
    }
}
