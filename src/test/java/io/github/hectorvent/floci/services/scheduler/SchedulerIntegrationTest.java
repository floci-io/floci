package io.github.hectorvent.floci.services.scheduler;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchedulerIntegrationTest {

    @Test
    @Order(1)
    void createScheduleGroup() {
        given()
            .contentType("application/json")
            .body("{\"ClientToken\":\"ct-1\"}")
        .when()
            .post("/schedule-groups/my-group")
        .then()
            .statusCode(200)
            .body("ScheduleGroupArn", containsString("schedule-group/my-group"))
            .body("ScheduleGroupArn", containsString(":scheduler:"));
    }

    @Test
    @Order(2)
    void createScheduleGroupWithTags() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ClientToken": "ct-2",
                    "Tags": [
                        {"Key": "env", "Value": "test"},
                        {"Key": "team", "Value": "platform"}
                    ]
                }
                """)
        .when()
            .post("/schedule-groups/tagged-group")
        .then()
            .statusCode(200)
            .body("ScheduleGroupArn", containsString("schedule-group/tagged-group"));
    }

    @Test
    @Order(3)
    void createScheduleGroupDuplicateReturns409() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/schedule-groups/my-group")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(4)
    void createScheduleGroupReservedDefaultNameReturns409() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/schedule-groups/default")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(5)
    void getScheduleGroup() {
        given()
        .when()
            .get("/schedule-groups/my-group")
        .then()
            .statusCode(200)
            .body("Name", equalTo("my-group"))
            .body("State", equalTo("ACTIVE"))
            .body("Arn", containsString("schedule-group/my-group"))
            .body("CreationDate", notNullValue())
            .body("LastModificationDate", notNullValue());
    }

    @Test
    @Order(6)
    void getDefaultScheduleGroupIsAutoCreated() {
        given()
        .when()
            .get("/schedule-groups/default")
        .then()
            .statusCode(200)
            .body("Name", equalTo("default"))
            .body("State", equalTo("ACTIVE"));
    }

    @Test
    @Order(7)
    void getScheduleGroupNotFoundReturns404() {
        given()
        .when()
            .get("/schedule-groups/nonexistent-group")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(8)
    void listScheduleGroupsIncludesDefault() {
        given()
        .when()
            .get("/schedule-groups")
        .then()
            .statusCode(200)
            .body("ScheduleGroups.Name", hasItem("default"))
            .body("ScheduleGroups.Name", hasItem("my-group"))
            .body("ScheduleGroups.Name", hasItem("tagged-group"));
    }

    @Test
    @Order(9)
    void listScheduleGroupsWithPrefix() {
        given()
            .queryParam("NamePrefix", "tag")
        .when()
            .get("/schedule-groups")
        .then()
            .statusCode(200)
            .body("ScheduleGroups.Name", hasItem("tagged-group"))
            .body("ScheduleGroups.Name", not(hasItem("my-group")))
            .body("ScheduleGroups.Name", not(hasItem("default")));
    }

    @Test
    @Order(10)
    void deleteScheduleGroup() {
        given()
        .when()
            .delete("/schedule-groups/my-group")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/schedule-groups/my-group")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(11)
    void deleteDefaultScheduleGroupReturns400() {
        given()
        .when()
            .delete("/schedule-groups/default")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(12)
    void deleteScheduleGroupNotFoundReturns404() {
        given()
        .when()
            .delete("/schedule-groups/already-gone")
        .then()
            .statusCode(404);
    }

    // ──────────────────────────── Tag tests ────────────────────────────

    private static final String TAGGED_GROUP_ARN =
            "arn:aws:scheduler:us-east-1:000000000000:schedule-group/tagged-group";

    @Test
    @Order(13)
    void listTagsReturnsTagsFromCreate() {
        // tagged-group was created at @Order(2) with env=test and team=platform.
        given()
        .when()
            .get("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("test"))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"));
    }

    @Test
    @Order(14)
    void tagResourceAddsTags() throws InterruptedException {
        // createScheduleGroup uses one Instant.now() for both CreationDate and
        // LastModificationDate (so they are byte-identical at creation). Sleep here so the
        // tagScheduleGroup Instant.now() below is guaranteed to be strictly later, even on
        // a system whose clock granularity collides with the test class's startup speed.
        Thread.sleep(2);

        given()
            .contentType("application/json")
            .body("""
                {
                    "Tags": [
                        {"Key": "owner", "Value": "Alice"},
                        {"Key": "env", "Value": "staging"}
                    ]
                }
                """)
        .when()
            .post("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'owner' }.Value", equalTo("Alice"))
            // overwrite of existing key
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("staging"))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"));

        // Tag mutation must bump LastModificationDate above the initial CreationDate
        // so a regression in this AWS-visible field is caught. Parse via Jackson directly
        // because RestAssured coerces sub-second epoch doubles to Float and loses the
        // sub-second delta the test relies on.
        String body = given()
        .when()
            .get("/schedule-groups/tagged-group")
        .then()
            .statusCode(200)
            .extract().asString();
        try {
            JsonNode tree = new ObjectMapper().readTree(body);
            double creation = tree.get("CreationDate").asDouble();
            double lastMod = tree.get("LastModificationDate").asDouble();
            assertThat(lastMod, greaterThan(creation));
        } catch (Exception e) {
            throw new AssertionError("Failed to parse schedule-group response: " + body, e);
        }
    }

    @Test
    @Order(15)
    void untagResourceRemovesKeys() {
        given()
            .queryParam("TagKeys", "owner")
            .queryParam("TagKeys", "env")
        .when()
            .delete("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'owner' }", nullValue())
            .body("Tags.find { it.Key == 'env' }", nullValue())
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"));
    }

    @Test
    @Order(16)
    void tagResourceOnMissingGroupReturns404() {
        String arn = "arn:aws:scheduler:us-east-1:000000000000:schedule-group/no-such-group";
        given()
            .contentType("application/json")
            .body("""
                {"Tags": [{"Key": "k", "Value": "v"}]}
                """)
        .when()
            .post("/tags/" + arn)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(16)
    void tagResourceWithEmptyBodyReturns400() {
        // Null/blank request body must surface as the structured AWS validation error
        // ("Value null at 'Tags' ...") rather than leaking a Jackson parser message.
        given()
            .contentType("application/json")
        .when()
            .post("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(16)
    void tagResourceWithNonObjectBodyReturns400() {
        // A syntactically valid JSON array at the root must be rejected as a wire-shape
        // error, not silently treated as "missing 'Tags'".
        given()
            .contentType("application/json")
            .body("[1,2,3]")
        .when()
            .post("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(17)
    void tagResourceOnScheduleArnReturns400() {
        // AWS only allows tagging schedule groups, not individual schedules.
        String arn = "arn:aws:scheduler:us-east-1:000000000000:schedule/default/some-schedule";
        given()
            .contentType("application/json")
            .body("""
                {"Tags": [{"Key": "k", "Value": "v"}]}
                """)
        .when()
            .post("/tags/" + arn)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(18)
    void tagResourceWithoutTagsReturns400() {
        // AWS spec: Tags is required on TagResource. Empty body must surface as
        // ValidationException rather than silently succeed.
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(19)
    void untagResourceWithoutTagKeysReturns400() {
        // AWS spec: TagKeys is required on UntagResource.
        given()
        .when()
            .delete("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(400);
    }

    // ──────────────────────────── Schedule tests ────────────────────────────

    @Test
    @Order(20)
    void createSchedule() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(1 hour)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "Target": {
                        "Arn": "arn:aws:lambda:us-east-1:000000000000:function:my-func",
                        "RoleArn": "arn:aws:iam::000000000000:role/scheduler-role"
                    }
                }
                """)
        .when()
            .post("/schedules/my-schedule")
        .then()
            .statusCode(200)
            .body("ScheduleArn", containsString("schedule/default/my-schedule"));
    }

    @Test
    @Order(20)
    void createScheduleWithSqsParametersRoundTrips() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(1 hour)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "Target": {
                        "Arn": "arn:aws:sqs:us-east-1:000000000000:my-queue.fifo",
                        "RoleArn": "arn:aws:iam::000000000000:role/scheduler-role",
                        "SqsParameters": {"MessageGroupId": "group-1"}
                    }
                }
                """)
        .when()
            .post("/schedules/fifo-schedule")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/schedules/fifo-schedule")
        .then()
            .statusCode(200)
            .body("Target.SqsParameters.MessageGroupId", equalTo("group-1"));
    }

    @Test
    @Order(21)
    void createScheduleWithEcsParametersRoundTrips() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(1 day)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "State": "DISABLED",
                    "Target": {
                        "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/proof",
                        "RoleArn": "arn:aws:iam::000000000000:role/scheduler-role",
                        "EcsParameters": {
                            "CapacityProviderStrategy": [{"CapacityProvider": "FARGATE", "Weight": 1, "Base": 0}],
                            "EnableECSManagedTags": true,
                            "EnableExecuteCommand": true,
                            "Group": "batch-group",
                            "TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1",
                            "LaunchType": "FARGATE",
                            "PlacementConstraints": [{"Type": "distinctInstance"}],
                            "PlacementStrategy": [{"Type": "spread", "Field": "attribute:ecs.availability-zone"}],
                            "TaskCount": 1,
                            "PlatformVersion": "1.4.0",
                            "PropagateTags": "TASK_DEFINITION",
                            "ReferenceId": "ref-123",
                            "Tags": [{"Key": "env", "Value": "test"}],
                            "NetworkConfiguration": {
                                "awsvpcConfiguration": {
                                    "Subnets": ["subnet-a"],
                                    "SecurityGroups": ["sg-a"],
                                    "AssignPublicIp": "DISABLED"
                                }
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/schedules/ecs-schedule")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/schedules/ecs-schedule")
        .then()
            .statusCode(200)
            .body("State", equalTo("DISABLED"))
            .body("Target.EcsParameters.CapacityProviderStrategy[0].CapacityProvider", equalTo("FARGATE"))
            .body("Target.EcsParameters.CapacityProviderStrategy[0].Weight", equalTo(1))
            .body("Target.EcsParameters.EnableECSManagedTags", equalTo(true))
            .body("Target.EcsParameters.EnableExecuteCommand", equalTo(true))
            .body("Target.EcsParameters.Group", equalTo("batch-group"))
            .body("Target.EcsParameters.TaskDefinitionArn", equalTo("arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1"))
            .body("Target.EcsParameters.LaunchType", equalTo("FARGATE"))
            .body("Target.EcsParameters.PlacementConstraints[0].Type", equalTo("distinctInstance"))
            .body("Target.EcsParameters.PlacementStrategy[0].Type", equalTo("spread"))
            .body("Target.EcsParameters.TaskCount", equalTo(1))
            .body("Target.EcsParameters.PlatformVersion", equalTo("1.4.0"))
            .body("Target.EcsParameters.PropagateTags", equalTo("TASK_DEFINITION"))
            .body("Target.EcsParameters.ReferenceId", equalTo("ref-123"))
            .body("Target.EcsParameters.Tags[0].Key", equalTo("env"))
            .body("Target.EcsParameters.NetworkConfiguration.awsvpcConfiguration.Subnets", contains("subnet-a"))
            .body("Target.EcsParameters.NetworkConfiguration.awsvpcConfiguration.SecurityGroups", contains("sg-a"))
            .body("Target.EcsParameters.NetworkConfiguration.awsvpcConfiguration.AssignPublicIp", equalTo("DISABLED"));

        given()
        .when()
            .delete("/schedules/ecs-schedule")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(22)
    void createScheduleInGroup() {
        // First create the group
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/schedule-groups/sched-test-group")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {
                    "GroupName": "sched-test-group",
                    "ScheduleExpression": "rate(5 minutes)",
                    "FlexibleTimeWindow": {"Mode": "FLEXIBLE", "MaximumWindowInMinutes": 10},
                    "Target": {
                        "Arn": "arn:aws:sqs:us-east-1:000000000000:my-queue",
                        "RoleArn": "arn:aws:iam::000000000000:role/r",
                        "Input": "hello"
                    },
                    "Description": "test schedule",
                    "State": "DISABLED"
                }
                """)
        .when()
            .post("/schedules/grouped-schedule")
        .then()
            .statusCode(200)
            .body("ScheduleArn", containsString("schedule/sched-test-group/grouped-schedule"));
    }

    @Test
    @Order(23)
    void createScheduleDuplicateReturns409() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(1 hour)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "Target": {"Arn": "arn:t", "RoleArn": "arn:r"}
                }
                """)
        .when()
            .post("/schedules/my-schedule")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(24)
    void getSchedule() {
        given()
        .when()
            .get("/schedules/my-schedule")
        .then()
            .statusCode(200)
            .body("Name", equalTo("my-schedule"))
            .body("GroupName", equalTo("default"))
            .body("State", equalTo("ENABLED"))
            .body("ScheduleExpression", equalTo("rate(1 hour)"))
            .body("FlexibleTimeWindow.Mode", equalTo("OFF"))
            .body("Target.Arn", containsString("function:my-func"))
            .body("Target.RoleArn", containsString("role/scheduler-role"))
            .body("CreationDate", notNullValue())
            .body("LastModificationDate", notNullValue());
    }

    @Test
    @Order(25)
    void getScheduleInGroup() {
        given()
            .queryParam("groupName", "sched-test-group")
        .when()
            .get("/schedules/grouped-schedule")
        .then()
            .statusCode(200)
            .body("Name", equalTo("grouped-schedule"))
            .body("GroupName", equalTo("sched-test-group"))
            .body("State", equalTo("DISABLED"))
            .body("Description", equalTo("test schedule"));
    }

    @Test
    @Order(26)
    void getScheduleNotFoundReturns404() {
        given()
        .when()
            .get("/schedules/nonexistent-schedule")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(27)
    void listSchedules() {
        given()
        .when()
            .get("/schedules")
        .then()
            .statusCode(200)
            .body("Schedules.Name", hasItem("my-schedule"))
            .body("Schedules.Name", hasItem("grouped-schedule"));
    }

    @Test
    @Order(28)
    void listSchedulesInGroup() {
        given()
            .queryParam("ScheduleGroup", "sched-test-group")
        .when()
            .get("/schedules")
        .then()
            .statusCode(200)
            .body("Schedules.Name", hasItem("grouped-schedule"))
            .body("Schedules.Name", not(hasItem("my-schedule")));
    }

    @Test
    @Order(29)
    void createScheduleWithDeadLetterConfig() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(10 minutes)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "Target": {
                        "Arn": "arn:aws:lambda:us-east-1:000000000000:function:dlc-func",
                        "RoleArn": "arn:aws:iam::000000000000:role/r",
                        "DeadLetterConfig": {
                            "Arn": "arn:aws:sqs:us-east-1:000000000000:my-dlq"
                        }
                    }
                }
                """)
        .when()
            .post("/schedules/dlc-schedule")
        .then()
            .statusCode(200)
            .body("ScheduleArn", containsString("schedule/default/dlc-schedule"));

        // Verify DeadLetterConfig is returned on get
        given()
        .when()
            .get("/schedules/dlc-schedule")
        .then()
            .statusCode(200)
            .body("Target.DeadLetterConfig.Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:my-dlq"));

        // Cleanup
        given()
        .when()
            .delete("/schedules/dlc-schedule")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(30)
    void createScheduleWithRetryPolicy() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(10 minutes)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "Target": {
                        "Arn": "arn:aws:lambda:us-east-1:000000000000:function:rp-func",
                        "RoleArn": "arn:aws:iam::000000000000:role/r",
                        "RetryPolicy": {
                            "MaximumEventAgeInSeconds": 3600,
                            "MaximumRetryAttempts": 5
                        }
                    }
                }
                """)
        .when()
            .post("/schedules/rp-schedule")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/schedules/rp-schedule")
        .then()
            .statusCode(200)
            .body("Target.RetryPolicy.MaximumEventAgeInSeconds", equalTo(3600))
            .body("Target.RetryPolicy.MaximumRetryAttempts", equalTo(5));

        // Cleanup
        given()
        .when()
            .delete("/schedules/rp-schedule")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(31)
    void createScheduleWithStartAndEndDate() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(1 hour)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "Target": {
                        "Arn": "arn:aws:lambda:us-east-1:000000000000:function:dated-func",
                        "RoleArn": "arn:aws:iam::000000000000:role/r"
                    },
                    "StartDate": 1780329600.0,
                    "EndDate": 1798761599.0
                }
                """)
        .when()
            .post("/schedules/dated-schedule")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/schedules/dated-schedule")
        .then()
            .statusCode(200)
            .body("StartDate", notNullValue())
            .body("EndDate", notNullValue());

        // Cleanup
        given()
        .when()
            .delete("/schedules/dated-schedule")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(32)
    void updateSchedule() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(30 minutes)",
                    "FlexibleTimeWindow": {"Mode": "FLEXIBLE", "MaximumWindowInMinutes": 5},
                    "Target": {
                        "Arn": "arn:aws:lambda:us-east-1:000000000000:function:updated-func",
                        "RoleArn": "arn:aws:iam::000000000000:role/updated-role"
                    },
                    "State": "DISABLED",
                    "Description": "updated description"
                }
                """)
        .when()
            .put("/schedules/my-schedule")
        .then()
            .statusCode(200)
            .body("ScheduleArn", containsString("schedule/default/my-schedule"));

        // Verify the update
        given()
        .when()
            .get("/schedules/my-schedule")
        .then()
            .statusCode(200)
            .body("ScheduleExpression", equalTo("rate(30 minutes)"))
            .body("State", equalTo("DISABLED"))
            .body("Description", equalTo("updated description"))
            .body("FlexibleTimeWindow.Mode", equalTo("FLEXIBLE"))
            .body("FlexibleTimeWindow.MaximumWindowInMinutes", equalTo(5));
    }

    @Test
    @Order(33)
    void updateScheduleNotFoundReturns404() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ScheduleExpression": "rate(1 hour)",
                    "FlexibleTimeWindow": {"Mode": "OFF"},
                    "Target": {"Arn": "arn:t", "RoleArn": "arn:r"}
                }
                """)
        .when()
            .put("/schedules/nonexistent-schedule")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(34)
    void deleteSchedule() {
        given()
        .when()
            .delete("/schedules/my-schedule")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/schedules/my-schedule")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(35)
    void deleteScheduleNotFoundReturns404() {
        given()
        .when()
            .delete("/schedules/already-gone-schedule")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(36)
    void deleteScheduleInGroup() {
        given()
            .queryParam("groupName", "sched-test-group")
        .when()
            .delete("/schedules/grouped-schedule")
        .then()
            .statusCode(200);

        given()
            .queryParam("groupName", "sched-test-group")
        .when()
            .get("/schedules/grouped-schedule")
        .then()
            .statusCode(404);
    }

    // ──────────────────────────── Tag validation tests ────────────────────────────

    @Test
    @Order(37)
    void tagResourceEntryMissingKeyOrValueReturns400() {
        // AWS Tag shape requires both Key and Value; entries with either missing
        // must surface as ValidationException, not be silently dropped.
        given()
            .contentType("application/json")
            .body("""
                {"Tags": [{"Key": "k"}]}
                """)
        .when()
            .post("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(37)
    void tagResourceTagsWrongTypeReturns400() {
        // Tags must be a list. An object or string in its place is a wire-format
        // error, not a missing value, so the message should differ from "Value null".
        given()
            .contentType("application/json")
            .body("""
                {"Tags": "not-a-list"}
                """)
        .when()
            .post("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(38)
    void tagResourceWithPutMethodReturns405() {
        // AWS Scheduler only defines POST for TagResource. PUT is not in the spec
        // and must be rejected so floci does not expose a non-AWS mutation route.
        given()
            .contentType("application/json")
            .body("""
                {"Tags": [{"Key": "k", "Value": "v"}]}
                """)
        .when()
            .put("/tags/" + TAGGED_GROUP_ARN)
        .then()
            .statusCode(405);
    }
}
