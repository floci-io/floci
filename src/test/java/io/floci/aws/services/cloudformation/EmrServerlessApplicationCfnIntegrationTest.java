package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.EmrServerlessRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import static io.floci.aws.testing.CfnStackWaits.output;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Provisions {@code AWS::EMRServerless::Application} through a stack and reads the application back through EMR
 * Serverless: the template's settings reach it in the API's shape, {@code Ref} and {@code Fn::GetAtt} name it, an
 * update changes it in place, a later resource's failure puts it back, a new {@code Name} replaces it, and deleting
 * the stack deletes it.
 */
@QuarkusTest
class EmrServerlessApplicationCfnIntegrationTest {

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("emr-serverless-app-cfn-it");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateRollBackReplaceAndDeleteAnApplication() {
        Stack created = stack.create(template("etl", 30, "v1", true, false));
        String id = output(created, "Ref");
        assertEquals(id, output(created, "ApplicationId"));
        assertEquals("arn:aws:emr-serverless:us-east-1:000000000000:/applications/" + id, output(created, "Arn"));
        getApplication(id).statusCode(200)
            .body("application.name", equalTo("etl"))
            .body("application.type", equalTo("SPARK"))
            .body("application.state", equalTo("CREATED"))
            .body("application.autoStopConfiguration.idleTimeoutMinutes", equalTo(30))
            .body("application.initialCapacity.Driver.workerCount", equalTo(2))
            .body("application.initialCapacity.Driver.workerConfiguration.cpu", equalTo("2 vCPU"))
            .body("application.monitoringConfiguration.cloudWatchLoggingConfiguration.logTypes.SPARK_DRIVER[0]",
                    equalTo("STDOUT"))
            .body("application.runtimeConfiguration[0].properties", hasEntry("spark.driver.cores", "2"))
            .body("application.tags", aMapWithSize(1))
            .body("application.tags.stage", equalTo("v1"));

        // The update drops InitialCapacity, which clears it, and changes the idle timeout and the tag in place.
        Stack updated = stack.update(template("etl", 45, "v2", false, false), "UPDATE_COMPLETE");
        assertEquals(id, output(updated, "Ref"));
        getApplication(id).statusCode(200)
            .body("application.autoStopConfiguration.idleTimeoutMinutes", equalTo(45))
            .body("application.initialCapacity", anEmptyMap())
            .body("application.tags.stage", equalTo("v2"));

        stack.update(template("etl", 60, "v3", false, true), "UPDATE_ROLLBACK_COMPLETE");
        getApplication(id).statusCode(200)
            .body("application.autoStopConfiguration.idleTimeoutMinutes", equalTo(45))
            .body("application.tags.stage", equalTo("v2"));

        // Name is create-only: a new one replaces the application, and the committed update deletes the old one.
        Stack replaced = stack.update(template("etl-2", 45, "v2", false, false), "UPDATE_COMPLETE");
        String replacement = output(replaced, "Ref");
        assertNotEquals(id, replacement);
        getApplication(replacement).statusCode(200).body("application.name", equalTo("etl-2"));
        getApplication(id).statusCode(200).body("application.state", equalTo("TERMINATED"));

        CfnStackWaits.deleteStack(stack.name());

        getApplication(replacement).statusCode(200).body("application.state", equalTo("TERMINATED"));
    }

    private static String template(String name, int idleMinutes, String stage, boolean initialCapacity,
                                   boolean laterFails) {
        return """
            {
              "Resources": {
                "App": {
                  "Type": "AWS::EMRServerless::Application",
                  "Properties": {
                    "Name": "%s",
                    "ReleaseLabel": "emr-7.5.0",
                    "Type": "SPARK",
                    "AutoStopConfiguration": {"Enabled": true, "IdleTimeoutMinutes": %d},%s
                    "MonitoringConfiguration": {"CloudWatchLoggingConfiguration": {"Enabled": true,
                      "LogTypeMap": [{"Key": "SPARK_DRIVER", "Value": ["STDOUT"]}]}},
                    "RuntimeConfiguration": [{"Classification": "spark-defaults",
                      "Properties": {"spark.driver.cores": "2"}}],
                    "Tags": [{"Key": "stage", "Value": "%s"}]
                  }
                }%s
              },
              "Outputs": {
                "Ref": {"Value": {"Ref": "App"}},
                "ApplicationId": {"Value": {"Fn::GetAtt": ["App", "ApplicationId"]}},
                "Arn": {"Value": {"Fn::GetAtt": ["App", "Arn"]}}
              }
            }
            """.formatted(name, idleMinutes, initialCapacity ? """

                    "InitialCapacity": [{"Key": "Driver", "Value": {"WorkerCount": 2,
                      "WorkerConfiguration": {"Cpu": "2 vCPU", "Memory": "4 GB"}}}],""" : "", stage,
                laterFails ? CfnStackWaits.failingResourceAfter("App") : "");
    }

    private static ValidatableResponse getApplication(String id) {
        return EmrServerlessRequests.emrServerless("us-east-1").when().get("/applications/" + id).then();
    }
}
