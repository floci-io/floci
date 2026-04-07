package io.github.hectorvent.floci.services.scheduler;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
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
    @Order(21)
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
    @Order(22)
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
    @Order(23)
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
    @Order(24)
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
    @Order(25)
    void getScheduleNotFoundReturns404() {
        given()
        .when()
            .get("/schedules/nonexistent-schedule")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(26)
    void listSchedules() {
        given()
        .when()
            .get("/schedules")
        .then()
            .statusCode(200)
            .body("Schedules.Name", hasItem("my-schedule"));
    }

    @Test
    @Order(27)
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
    @Order(28)
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
    @Order(29)
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
    @Order(30)
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
    @Order(31)
    void deleteScheduleNotFoundReturns404() {
        given()
        .when()
            .delete("/schedules/already-gone-schedule")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(32)
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
}
