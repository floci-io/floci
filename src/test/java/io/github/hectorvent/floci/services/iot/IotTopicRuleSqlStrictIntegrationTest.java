package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.testing.IotSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code floci.services.iot.rule-sql-strict} on, a topic rule whose SQL is outside the subset
 * Floci evaluates is rejected with {@code SqlParseException}, while one inside it is still accepted.
 * Off, the default, such a rule is stored and fires on every matching topic with the whole payload.
 *
 * <p>The setting comes from {@link IotSettingsProfile}, which this class shares with the other IoT
 * class that changes a default.
 */
@QuarkusTest
@TestProfile(IotSettingsProfile.class)
class IotTopicRuleSqlStrictIntegrationTest {

    @Test
    void strictModeRejectsAStatementOutsideTheSubset() {
        given()
            .contentType("application/json")
            .body(rule("SELECT principal() as p FROM 'strict/rules/+'"))
        .when()
            .put("/rules/strictRejectedRule")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SqlParseException"))
            .body("message", containsString("principal"));
    }

    @Test
    void strictModeAcceptsAStatementInsideTheSubset() {
        given()
            .contentType("application/json")
            .body(rule("SELECT *, topic() as topic FROM 'strict/rules/+' WHERE endswith(clientToken, 'inbound')"))
        .when()
            .put("/rules/strictAcceptedRule")
        .then()
            .statusCode(200);
    }

    private String rule(String sql) {
        return """
                {
                  "topicRulePayload": {
                    "sql": "%s",
                    "actions": [
                      {
                        "republish": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "topic": "strict/rules-out"
                        }
                      }
                    ]
                  }
                }
                """.formatted(sql);
    }
}
