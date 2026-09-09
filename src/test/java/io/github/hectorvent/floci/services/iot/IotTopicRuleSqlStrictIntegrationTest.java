package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IotTopicRuleSqlStrictIntegrationTest.StrictRuleSqlProfile.class)
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

    public static final class StrictRuleSqlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iot.rule-sql-strict", "true");
        }
    }
}
