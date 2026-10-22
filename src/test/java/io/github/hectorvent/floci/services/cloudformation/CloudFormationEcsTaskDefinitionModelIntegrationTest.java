package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::ECS::TaskDefinition} read as the ECS handler reads it. Measured on AWS (eu-west-1,
 * 2026-10-19): CloudFormation hands the properties over without template validation, every scalar
 * as text, and the handler converts them to its model's types. A value it cannot convert fails the
 * resource with every violation of the unconverted model, the Memory it could convert included;
 * a model that converts but lacks a required member fails with that member alone.
 */
@QuarkusTest
class CloudFormationEcsTaskDefinitionModelIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113";
    private static final String MEMORY = "#/ContainerDefinitions/0/Memory: expected type: Integer, found: String."
            + " This may occur when a numeric value exceeds 32-bit integer max (2147483647). Consider using 'number'"
            + " type or ensure value fits in integer range.";
    /** A String and a CommaDelimitedList parameter of Linux capabilities. */
    private static final String PARAMETERS = """
            "Parameters": {
              "Str": {"Type": "String", "Default": "SYS_PTRACE"},
              "Csv": {"Type": "CommaDelimitedList", "Default": "SYS_PTRACE,NET_ADMIN"}
            },
            """;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aValueTheHandlerCannotConvertFailsTheCreateWithEveryViolation() {
        String memory = stackName("memory");
        submit("CreateStack", memory, template("", memory, "\"Name\": \"app\", \"Image\": \"busybox\", \"Memory\": \"abc\""));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(memory, CFN_AUTH).status());
        assertEventReason(memory, "Model validation failed (" + MEMORY + ")");

        String add = stackName("add");
        submit("CreateStack", add, template(PARAMETERS, add, "\"Name\": \"app\", \"Image\": \"busybox\", \"Memory\": 128,"
                + " \"LinuxParameters\": {\"Capabilities\": {\"Add\": {\"Ref\": \"Str\"}}}"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(add, CFN_AUTH).status());
        assertEventReason(add, "Model validation failed (" + MEMORY + "\n#/ContainerDefinitions/0/LinuxParameters"
                + "/Capabilities/Add: expected type: JSONArray, found: String)");
    }

    @Test
    void aMissingRequiredMemberFailsTheCreateAlone() {
        String image = stackName("image");
        submit("CreateStack", image, template("", image, "\"Name\": \"app\", \"Memory\": 128"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(image, CFN_AUTH).status());
        assertEventReason(image, "Model validation failed (#/ContainerDefinitions/0: required key [Image] not found)");
    }

    @Test
    void valuesTheHandlerConvertsAreRegisteredConverted() {
        String converted = stackName("converted");
        submit("CreateStack", converted, template(PARAMETERS, converted, "\"Name\": \"app\", \"Image\": \"busybox\","
                + " \"Memory\": \"+128\", \"LinuxParameters\": {\"Capabilities\": {\"Add\": {\"Ref\": \"Csv\"}}}},"
                + " {\"Name\": \"side\", \"Image\": \"busybox\", \"Memory\": 64, \"Essential\": \"False\""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(converted, CFN_AUTH).status());

        RestAssuredJsonUtils.awsAction(ECS_TARGET, "DescribeTaskDefinition", "{\"taskDefinition\": \"" + converted + "\"}")
        .then()
            .statusCode(200)
            .body("taskDefinition.containerDefinitions.find { it.name == 'app' }.memory", equalTo(128))
            .body("taskDefinition.containerDefinitions.find { it.name == 'side' }.essential", equalTo(false));
    }

    /** Container and task properties the runtime honours are registered, not dropped. */
    @Test
    void containerAndTaskLevelPropertiesAreRegistered() {
        String family = stackName("props");
        submit("CreateStack", family, """
                {
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "%s",
                        "PidMode": "task",
                        "EphemeralStorage": {"SizeInGiB": 30},
                        "Volumes": [{"Name": "data"}],
                        "ContainerDefinitions": [
                          {"Name": "init", "Image": "busybox", "Memory": 64, "Essential": false},
                          {"Name": "app", "Image": "busybox", "Memory": 128, "WorkingDirectory": "/app",
                           "User": "1000", "StopTimeout": 30,
                           "MountPoints": [{"SourceVolume": "data", "ContainerPath": "/data"}],
                           "DependsOn": [{"ContainerName": "init", "Condition": "COMPLETE"}]}
                        ]
                      }
                    }
                  }
                }
                """.formatted(family));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(family, CFN_AUTH).status());

        RestAssuredJsonUtils.awsAction(ECS_TARGET, "DescribeTaskDefinition", "{\"taskDefinition\": \"" + family + "\"}")
        .then()
            .statusCode(200)
            .body("taskDefinition.pidMode", equalTo("task"))
            .body("taskDefinition.ephemeralStorage.sizeInGiB", equalTo(30))
            .body("taskDefinition.volumes.name", hasItem("data"))
            .body("taskDefinition.containerDefinitions.find { it.name == 'app' }.workingDirectory", equalTo("/app"))
            .body("taskDefinition.containerDefinitions.find { it.name == 'app' }.user", equalTo("1000"))
            .body("taskDefinition.containerDefinitions.find { it.name == 'app' }.stopTimeout", equalTo(30))
            .body("taskDefinition.containerDefinitions.find { it.name == 'app' }.mountPoints[0].containerPath",
                    equalTo("/data"))
            .body("taskDefinition.containerDefinitions.find { it.name == 'app' }.dependsOn[0].condition",
                    equalTo("COMPLETE"));
    }

    /**
     * A host volume's source path goes through the same policy as one registered through the ECS
     * API, at registration, so a path it refuses fails the resource instead of the task later.
     */
    @Test
    void aHostVolumeThePolicyRefusesFailsTheCreate() {
        String family = stackName("host");
        submit("CreateStack", family, """
                {
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "%s",
                        "Volumes": [{"Name": "data", "Host": {"SourcePath": "relative/data"}}],
                        "ContainerDefinitions": [{"Name": "app", "Image": "busybox", "Memory": 128,
                          "MountPoints": [{"SourceVolume": "data", "ContainerPath": "/data"}]}]
                      }
                    }
                  }
                }
                """.formatted(family));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(family, CFN_AUTH).status());
        assertEventReason(family, "volumes[].host.sourcePath must be an absolute path: relative/data");
    }

    private String stackName(String purpose) {
        String name = "cfn-ecs-model-" + purpose + "-" + Long.toString(System.nanoTime(), 36);
        cleanup.register(() -> {
            submit("DeleteStack", name, null);
            CfnStackWaits.awaitStackDeleted(name, CFN_AUTH);
        });
        return name;
    }

    /**
     * A task definition of {@code family} whose containers are {@code containers}, the members of
     * each without its braces, after the template sections {@code sections}.
     */
    private static String template(String sections, String family, String containers) {
        return """
                {
                  %s
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "%s",
                        "ContainerDefinitions": [{%s}]
                      }
                    }
                  }
                }
                """.formatted(sections, family, containers);
    }

    private static void submit(String action, String stackName, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /** The stack has an event whose reason is {@code reason}, read from the XML so its escapes are undone. */
    private static void assertEventReason(String stackName, String reason) {
        List<String> reasons = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getList("**.findAll { it.name() == 'ResourceStatusReason' }", String.class);
        assertThat(reasons, hasItem(reason));
    }
}
