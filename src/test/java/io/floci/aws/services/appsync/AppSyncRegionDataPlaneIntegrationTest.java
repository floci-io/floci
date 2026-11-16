package io.floci.aws.services.appsync;

import io.floci.aws.testing.AppSyncRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * A GraphQL API's endpoint names no Region, so a query reaches an API of any Region: one authorized by an API key alone
 * carries no Region, runs as the default Region's request, and is still answered by an API of another Region, in it.
 * The answer comes from the API's resolver, which is found only in the API's Region, so a query left in the default
 * Region would answer {@code null}.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AppSyncRegionDataPlaneIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "eu-west-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anApiOfAnotherRegionThanTheDefaultAnswersAQueryThroughItsResolver() {
        String apiId = in().body("{\"name\":\"west-orders\",\"authenticationType\":\"API_KEY\"}").post("/v1/apis")
                .then().statusCode(200).extract().path("graphqlApi.apiId");
        cleanup.register(() -> in().delete("/v1/apis/" + apiId));
        String key = in().body("{}").post("/v1/apis/" + apiId + "/apikeys").then().statusCode(200)
                .extract().path("apiKey.id");
        in().body("{\"definition\":\"type Query { hello: String }\"}").post("/v1/apis/" + apiId + "/schemacreation")
                .then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).until(() -> "SUCCESS".equals(
                in().get("/v1/apis/" + apiId + "/schemacreation").then().statusCode(200).extract().path("status")));
        in().body("{\"name\":\"local\",\"type\":\"NONE\"}").post("/v1/apis/" + apiId + "/datasources")
                .then().statusCode(200);
        in().body("{\"fieldName\":\"hello\",\"dataSourceName\":\"local\",\"requestMappingTemplate\":"
                + "\"{\\\"version\\\":\\\"2018-05-29\\\",\\\"payload\\\":\\\"west\\\"}\","
                + "\"responseMappingTemplate\":\"$util.toJson($ctx.result)\"}")
                .post("/v1/apis/" + apiId + "/types/Query/resolvers").then().statusCode(200);

        given().header("x-api-key", key).contentType("application/json").body("{\"query\":\"{ hello }\"}")
                .post("/v1/apis/" + apiId + "/graphql")
                .then().statusCode(200).body("errors", nullValue()).body("data.hello", equalTo("west"));
    }

    private static RequestSpecification in() {
        return AppSyncRequests.signed(ACCOUNT, REGION);
    }
}
