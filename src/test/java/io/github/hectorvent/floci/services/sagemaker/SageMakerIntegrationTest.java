package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class SageMakerIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";

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

    private io.restassured.response.Response post(String target, String body) {
        return given().header("Authorization", AUTH)
                .header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
