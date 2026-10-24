package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiStage;
import software.amazon.awssdk.services.apigateway.model.CreateUsagePlanResponse;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlanResponse;
import software.amazon.awssdk.services.apigateway.model.QuotaPeriodType;
import software.amazon.awssdk.services.apigateway.model.QuotaSettings;
import software.amazon.awssdk.services.apigateway.model.ThrottleSettings;
import software.amazon.awssdk.services.apigateway.model.UsagePlan;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ApiGatewayUsagePlanSdkIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    private ApiGatewayClient client() {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    void stagesThrottleAndQuotaRoundTripThroughEveryRead() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(r -> r.name("usage-plan-sdk")).id();
            ThrottleSettings planThrottle = ThrottleSettings.builder().burstLimit(20).rateLimit(10.0).build();
            QuotaSettings quota = QuotaSettings.builder().limit(1000).offset(3).period(QuotaPeriodType.MONTH).build();
            ApiStage v1 = ApiStage.builder().apiId(apiId).stage("v1")
                    .throttle(Map.of("/pets/GET", ThrottleSettings.builder().burstLimit(10).rateLimit(5.0).build()))
                    .build();
            ApiStage v2 = ApiStage.builder().apiId(apiId).stage("v2").build();
            CreateUsagePlanResponse created = client.createUsagePlan(r -> r.name("sdk-plan")
                    .apiStages(v1, v2).throttle(planThrottle).quota(quota));
            try {
                assertEquals(List.of(v1, v2), created.apiStages());
                assertEquals(planThrottle, created.throttle());
                assertEquals(quota, created.quota());

                GetUsagePlanResponse read = client.getUsagePlan(r -> r.usagePlanId(created.id()));
                assertEquals(List.of(v1, v2), read.apiStages());
                assertEquals(planThrottle, read.throttle());
                assertEquals(quota, read.quota());

                UsagePlan listed = client.getUsagePlans(r -> r.limit(500)).items().stream()
                        .filter(plan -> plan.id().equals(created.id())).findFirst().orElseThrow();
                assertEquals(List.of(v1, v2), listed.apiStages());
                assertEquals(planThrottle, listed.throttle());
                assertEquals(quota, listed.quota());
            } finally {
                client.deleteUsagePlan(r -> r.usagePlanId(created.id()));
                client.deleteRestApi(r -> r.restApiId(apiId));
            }
        }
    }
}
