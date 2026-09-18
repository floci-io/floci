package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A state reset runs every container teardown, and the SageMaker training runner and endpoint
 * manager shut their worker pools down there. Without restoring them afterwards, every later
 * CreateTrainingJob, CreateEndpoint and UpdateEndpoint failed with a 500 until the emulator
 * restarted. The requests submit their work before touching Docker, so no daemon is needed.
 */
@QuarkusTest
class SageMakerResetIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";
    private static final String UNPULLABLE_IMAGE = "floci.invalid/none:latest";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void trainingJobsAndEndpointsCanStillBeCreatedAfterAStateReset() {
        given().when().post("/_floci/state/reset").then().statusCode(200);
        try {
            String suffix = Long.toString(System.nanoTime(), 36);
            post("SageMaker.CreateTrainingJob", """
                    {"TrainingJobName":"sm-reset-%s",
                     "AlgorithmSpecification":{"TrainingImage":"%s","TrainingInputMode":"File"},
                     "OutputDataConfig":{"S3OutputPath":"s3://sm-reset-%s/output"},
                     "ResourceConfig":{"InstanceType":"ml.m5.large","InstanceCount":1,"VolumeSizeInGB":1},
                     "StoppingCondition":{"MaxRuntimeInSeconds":60}}
                    """.formatted(suffix, UNPULLABLE_IMAGE, suffix))
                    .then().statusCode(200).body("TrainingJobArn", notNullValue());

            String model = "sm-reset-model-" + suffix;
            post("SageMaker.CreateModel", """
                    {"ModelName":"%s","PrimaryContainer":{"Image":"%s"}}
                    """.formatted(model, UNPULLABLE_IMAGE))
                    .then().statusCode(200);
            String config = "sm-reset-cfg-" + suffix;
            post("SageMaker.CreateEndpointConfig", """
                    {"EndpointConfigName":"%s","ProductionVariants":[{"VariantName":"AllTraffic","ModelName":"%s","InitialInstanceCount":1,"InstanceType":"ml.t2.medium"}]}
                    """.formatted(config, model))
                    .then().statusCode(200);
            post("SageMaker.CreateEndpoint", """
                    {"EndpointName":"sm-reset-ep-%s","EndpointConfigName":"%s"}
                    """.formatted(suffix, config))
                    .then().statusCode(200).body("EndpointArn", notNullValue());
        } finally {
            // Floci has no DeleteTrainingJob, so a second reset is what leaves nothing behind.
            given().when().post("/_floci/state/reset").then().statusCode(200);
        }
    }

    private Response post(String target, String body) {
        return given().header("Authorization", AUTH)
                .header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
