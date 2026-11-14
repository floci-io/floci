package io.floci.aws.services.appsync.graphql.datasource;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class HttpDataSourceBatchSidecarIntegrationTest {

    @InjectMock
    HttpDataSourceTransport transport;

    private final CountDownLatch started = new CountDownLatch(2);
    private AppSyncClient client;
    private String apiId;
    private String apiKey;

    @BeforeAll
    static void requireSidecar() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createApi() {
        reset(transport);
        client = AwsTestClients.create(AppSyncClient.builder(), "test", Region.US_EAST_1);
        apiId = client.createGraphqlApi(r -> r.name("http-batch-" + UUID.randomUUID()).authenticationType("API_KEY"))
                .graphqlApi().apiId();
        apiKey = client.createApiKey(r -> r.apiId(apiId)).apiKey().id();
        client.startSchemaCreation(r -> r.apiId(apiId).definition(SdkBytes.fromUtf8String(
                "type Query { first: String second: String } type Mutation { first: String second: String }")));
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCESS".equals(
                client.getSchemaCreationStatus(r -> r.apiId(apiId)).statusAsString()));
        client.createDataSource(r -> r.apiId(apiId).name("Http").type("HTTP")
                .httpConfig(h -> h.endpoint("https://public.example")));
        for (String type : List.of("Query", "Mutation")) {
            for (String field : List.of("first", "second")) {
                client.createResolver(r -> r.apiId(apiId).typeName(type).fieldName(field).dataSourceName("Http")
                        .kind("UNIT").requestMappingTemplate("""
                                {"version":"2018-05-29","method":"GET","resourcePath":"/%s"}
                                """.formatted(field)).responseMappingTemplate("$util.toJson($ctx.result.body)"));
            }
        }
    }

    @AfterEach
    void cleanUp() {
        started.countDown();
        started.countDown();
        try {
            if (apiId != null) {
                client.deleteGraphqlApi(r -> r.apiId(apiId));
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void independentHttpFieldsDoNotShareASequentialCallbackDeadline() {
        when(transport.send(any())).thenAnswer(call -> {
            started.countDown();
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Independent HTTP fields did not start concurrently");
            }
            HttpDataSourceRequest request = call.getArgument(0);
            return Map.of("statusCode", 200, "headers", Map.of(), "body", request.uri().getPath());
        });
        given().header("x-api-key", apiKey).contentType("application/json")
                .body(Map.of("query", "{ first second }"))
                .when().post("/v1/apis/" + apiId + "/graphql")
                .then().statusCode(200).body("errors", nullValue())
                .body("data.first", equalTo("/first")).body("data.second", equalTo("/second"));
    }
    @Test
    void rootMutationsStillRunInSelectionOrder() {
        List<String> calls = new CopyOnWriteArrayList<>();
        when(transport.send(any())).thenAnswer(call -> {
            HttpDataSourceRequest request = call.getArgument(0);
            calls.add(request.uri().getPath());
            return Map.of("statusCode", 200, "headers", Map.of(), "body", String.join(",", calls));
        });
        given().header("x-api-key", apiKey).contentType("application/json")
                .body(Map.of("query", "mutation { first second }"))
                .when().post("/v1/apis/" + apiId + "/graphql")
                .then().statusCode(200).body("errors", nullValue())
                .body("data.first", equalTo("/first")).body("data.second", equalTo("/first,/second"));
    }

}
