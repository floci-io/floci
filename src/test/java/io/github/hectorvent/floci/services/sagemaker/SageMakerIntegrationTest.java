package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.ListModelsResponse;

import java.net.URI;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class SageMakerIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";
    private static final String OTHER_ACCOUNT_AUTH =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260205/us-east-1/sagemaker/aws4_request";
    // A model without an image fails its endpoint start before any container is created, so the
    // failure reason proves which account's model the start worker resolved, without Docker.
    private static final String IMAGE_REQUIRED = "PrimaryContainer.Image is required";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void jsonControlPlaneModelEndpointConfigAndTags() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String model = "sm-model-" + suffix;
        String arn = post("SageMaker.CreateModel", """
                {"ModelName":"%s","PrimaryContainer":{"Image":"busybox:stable"},"Tags":[{"Key":"env","Value":"test"}]}
                """.formatted(model))
                .then().statusCode(200).body("ModelArn", notNullValue()).extract().path("ModelArn");

        post("SageMaker.DescribeModel", "{\"ModelName\":\"%s\"}".formatted(model))
                .then().statusCode(200).body("ModelName", equalTo(model)).body("PrimaryContainer.Image", equalTo("busybox:stable"));

        String cfg = "sm-cfg-" + suffix;
        post("SageMaker.CreateEndpointConfig", """
                {"EndpointConfigName":"%s","ProductionVariants":[{"VariantName":"AllTraffic","ModelName":"%s","InitialInstanceCount":1,"InstanceType":"ml.t2.medium","InitialVariantWeight":1.0}]}
                """.formatted(cfg, model))
                .then().statusCode(200).body("EndpointConfigArn", notNullValue());
        post("SageMaker.ListEndpointConfigs", "{}").then().statusCode(200)
                .body("EndpointConfigs.find { it.EndpointConfigName == '%s' }.EndpointConfigName".formatted(cfg), equalTo(cfg));

        post("SageMaker.AddTags", "{\"ResourceArn\":\"%s\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"sdk\"}]}".formatted(arn))
                .then().statusCode(200).body("Tags", hasSize(2));
        post("SageMaker.DeleteTags", "{\"ResourceArn\":\"%s\",\"TagKeys\":[\"env\"]}".formatted(arn))
                .then().statusCode(200);
        post("SageMaker.ListTags", "{\"ResourceArn\":\"%s\"}".formatted(arn))
                .then().statusCode(200).body("Tags", hasSize(1)).body("Tags[0].Key", equalTo("owner"));

        post("SageMaker.DeleteEndpointConfig", "{\"EndpointConfigName\":\"%s\"}".formatted(cfg)).then().statusCode(200);
        post("SageMaker.DeleteModel", "{\"ModelName\":\"%s\"}".formatted(model)).then().statusCode(200);
    }

    @Test
    void trainingJobValidationErrorsUseAwsJsonErrorShape() {
        post("SageMaker.CreateTrainingJob", "{\"TrainingJobName\":\"bad\"}")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void listModelsReportsAllInvalidSortEnums() {
        String sortByError = "Value 'BadBy' at 'sortBy' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [Name, CreationTime]";
        String sortOrderError = "Value 'BadOrder' at 'sortOrder' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [Ascending, Descending]";

        post("SageMaker.ListModels", "{\"SortBy\":\"BadBy\",\"SortOrder\":\"BadOrder\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("2 validation errors detected: " + sortByError + "; " + sortOrderError));
        post("SageMaker.ListModels", "{\"SortBy\":\"BadBy\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: " + sortByError));
        post("SageMaker.ListModels", "{\"SortOrder\":\"BadOrder\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: " + sortOrderError));
    }

    @Test
    void listModels_respectsRequestedNameDescendingOrder() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String first = "a-model-" + suffix;
        String second = "z-model-" + suffix;
        for (String name : new String[] {first, second}) {
            post("SageMaker.CreateModel", """
                    {"ModelName":"%s","PrimaryContainer":{"Image":"busybox:stable"}}
                    """.formatted(name)).then().statusCode(200);
        }

        post("SageMaker.ListModels", """
                {"NameContains":"%s","SortBy":"Name","SortOrder":"Descending"}
                """.formatted(suffix))
                .then().statusCode(200)
                .body("Models.ModelName", contains(second, first));

        try (SageMakerClient client = SageMakerClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            ListModelsResponse firstPage = client.listModels(r -> r.nameContains(suffix)
                    .sortBy("Name").sortOrder("Descending").maxResults(1));
            assertEquals(second, firstPage.models().get(0).modelName());
            assertNotNull(firstPage.nextToken());

            ListModelsResponse secondPage = client.listModels(r -> r.nameContains(suffix)
                    .sortBy("Name").sortOrder("Descending").maxResults(1)
                    .nextToken(firstPage.nextToken()));
            assertEquals(first, secondPage.models().get(0).modelName());
            assertNull(secondPage.nextToken());

            ListModelsResponse defaultPage = client.listModels(r -> r.nameContains(suffix).maxResults(1));
            assertNotNull(defaultPage.nextToken());
            ListModelsResponse defaultNext = client.listModels(r -> r.nameContains(suffix)
                    .maxResults(1).nextToken(defaultPage.nextToken()));
            assertEquals(1, defaultNext.models().size());
            assertNotEquals(defaultPage.models().get(0).modelName(), defaultNext.models().get(0).modelName());
        } finally {
            post("SageMaker.DeleteModel", "{\"ModelName\":\"%s\"}".formatted(first)).then().statusCode(200);
            post("SageMaker.DeleteModel", "{\"ModelName\":\"%s\"}".formatted(second)).then().statusCode(200);
        }
    }

    @Test
    void endpointStartInAnotherAccountResolvesThatAccountsModel() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String endpoint = "sm-acct-ep-" + suffix;
        createImagelessEndpoint(OTHER_ACCOUNT_AUTH, "sm-acct-model-" + suffix, "sm-acct-cfg-" + suffix, endpoint);

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> endpointField(OTHER_ACCOUNT_AUTH, endpoint, "EndpointStatus"), equalTo("Failed"));
        assertThat(endpointField(OTHER_ACCOUNT_AUTH, endpoint, "FailureReason"), equalTo(IMAGE_REQUIRED));
    }

    @Test
    void endpointStartInAnotherAccountLeavesTheDefaultAccountsSameNamedEndpointAlone() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String model = "sm-shared-model-" + suffix;
        String cfg = "sm-shared-cfg-" + suffix;
        String endpoint = "sm-shared-ep-" + suffix;
        createImagelessEndpoint(AUTH, model, cfg, endpoint);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> endpointField(AUTH, endpoint, "EndpointStatus"), equalTo("Failed"));
        String defaultArn = endpointField(AUTH, endpoint, "EndpointArn");
        assertThat(defaultArn, containsString(":000000000000:"));

        createImagelessEndpoint(OTHER_ACCOUNT_AUTH, model, cfg, endpoint);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> !"Creating".equals(endpointField(OTHER_ACCOUNT_AUTH, endpoint, "EndpointStatus"))
                        || !defaultArn.equals(endpointField(AUTH, endpoint, "EndpointArn")));

        assertThat(endpointField(AUTH, endpoint, "EndpointArn"), equalTo(defaultArn));
        assertThat(endpointField(OTHER_ACCOUNT_AUTH, endpoint, "EndpointArn"), containsString(":111111111111:"));
        assertThat(endpointField(OTHER_ACCOUNT_AUTH, endpoint, "EndpointStatus"), equalTo("Failed"));
        assertThat(endpointField(OTHER_ACCOUNT_AUTH, endpoint, "FailureReason"), equalTo(IMAGE_REQUIRED));
    }

    private void createImagelessEndpoint(String auth, String model, String cfg, String endpoint) {
        post(auth, "SageMaker.CreateModel", """
                {"ModelName":"%s","PrimaryContainer":{"Environment":{"MODE":"test"}}}
                """.formatted(model)).then().statusCode(200);
        post(auth, "SageMaker.CreateEndpointConfig", """
                {"EndpointConfigName":"%s","ProductionVariants":[{"VariantName":"AllTraffic","ModelName":"%s","InitialInstanceCount":1,"InstanceType":"ml.t2.medium","InitialVariantWeight":1.0}]}
                """.formatted(cfg, model)).then().statusCode(200);
        post(auth, "SageMaker.CreateEndpoint", "{\"EndpointName\":\"%s\",\"EndpointConfigName\":\"%s\"}".formatted(endpoint, cfg))
                .then().statusCode(200);
    }

    private String endpointField(String auth, String endpoint, String field) {
        return post(auth, "SageMaker.DescribeEndpoint", "{\"EndpointName\":\"%s\"}".formatted(endpoint))
                .then().statusCode(200).extract().path(field);
    }

    private Response post(String target, String body) {
        return post(AUTH, target, body);
    }

    private Response post(String auth, String target, String body) {
        return given().header("Authorization", auth)
                .header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
