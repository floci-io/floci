package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.StepFunctionsTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsNestedTaskIamIntegrationTest {
    private static final String SOURCE = "000000000431";
    private static final String TARGET = "000000000432";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASS = """
            {"StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
            """;
    private static final String STATES_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
             "Principal":{"Service":"states.amazonaws.com"}}]}
            """;

    @TestHTTPResource
    URI baseUri;

    @InjectSpy
    JsonataEvaluator jsonataEvaluator;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync",
            "states:startExecution.sync:2", "aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution"})
    void allowedNestedTasksUseTheAssumedRoleAccount(String integration) throws Exception {
        Fixture fixture = fixture(integration, "allow");
        String execution = start(fixture.parent());
        Response completed = terminal(execution);
        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        if (integration.endsWith("startSyncExecution")) {
            JsonNode output = MAPPER.readTree(completed.jsonPath().getString("output"));
            assertEquals(fixture.child(), output.path("StateMachineArn").asText());
            assertEquals("SUCCEEDED", output.path("Status").asText());
        } else {
            request(TARGET, "ListExecutions", Map.of("stateMachineArn", fixture.child()))
                    .then().statusCode(200).body("executions.size()", equalTo(1));
        }
        request(SOURCE, "GetExecutionHistory", Map.of("executionArn", execution))
                .then().statusCode(200);
        request(TARGET, "DescribeExecution", Map.of("executionArn", execution))
                .then().statusCode(400);
    }

    @ParameterizedTest
    @CsvSource({"states:startExecution,assume-deny", "aws-sdk:sfn:startExecution,assume-deny",
            "states:startExecution,trust-deny", "aws-sdk:sfn:startExecution,trust-deny",
            "states:startExecution,start-deny", "aws-sdk:sfn:startExecution,start-deny"})
    void deniedAssumptionOrTaskPermissionsDoNotStartAChild(String integration, String scenario) throws Exception {
        Fixture fixture = fixture(integration, scenario);
        Response completed = terminal(start(fixture.parent()));
        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        String expectedError = "start-deny".equals(scenario)
                ? (integration.startsWith("aws-sdk:") ? "Sfn.SfnException" : "StepFunctions.AWSStepFunctionsException")
                : "States.TaskFailed";
        assertEquals(expectedError, completed.jsonPath().getString("error"), completed.asString());
        if ("start-deny".equals(scenario)) {
            assertTrue(completed.jsonPath().getString("cause").contains("states:StartExecution"), completed.asString());
        } else {
            assertEquals("The role " + fixture.executionRole()
                    + " is not authorized to assume the task state's role, " + fixture.taskRole() + ".",
                    completed.jsonPath().getString("cause"));
        }
        request(TARGET, "ListExecutions", Map.of("stateMachineArn", fixture.child()))
                .then().statusCode(200).body("executions.size()", equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "aws-sdk:sfn:startExecution"})
    void jsonataTaskCredentialsAreResolvedOnce(String integration) throws Exception {
        Fixture fixture = fixture(integration, "allow");
        String definition = MAPPER.writeValueAsString(Map.of(
                "QueryLanguage", "JSONata", "StartAt", "Child", "States", Map.of("Child", Map.of(
                        "Type", "Task", "Resource", "arn:aws:states:::" + integration,
                        "Credentials", Map.of("RoleArn", "{% $states.input.role %}"),
                        "Arguments", Map.of("StateMachineArn", fixture.child(), "Input", Map.of()),
                        "End", true))));
        request(SOURCE, "UpdateStateMachine", Map.of(
                "stateMachineArn", fixture.parent(), "definition", definition)).then().statusCode(200);
        clearInvocations(jsonataEvaluator);

        Response started = request(SOURCE, "StartExecution", Map.of(
                "stateMachineArn", fixture.parent(),
                "input", MAPPER.writeValueAsString(Map.of("role", fixture.taskRole()))));
        started.then().statusCode(200);
        Response completed = terminal(started.jsonPath().getString("executionArn"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        verify(jsonataEvaluator, times(1)).resolveTemplate(any(), eq("Credentials"), any(), any());
        request(TARGET, "ListExecutions", Map.of("stateMachineArn", fixture.child()))
                .then().statusCode(200).body("executions.size()", equalTo(1));
    }

    private Fixture fixture(String integration, String scenario) throws Exception {
        String executionRole = role(SOURCE, STATES_TRUST, """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Action":"sts:AssumeRole","Resource":"*"}]}
                """.formatted("assume-deny".equals(scenario) ? "Deny" : "Allow"));
        String childRole = role(TARGET, STATES_TRUST, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"states:*","Resource":"*"}]}
                """);
        String child = machine(TARGET, PASS,
                integration.endsWith("startSyncExecution") ? "EXPRESS" : "STANDARD", childRole);
        String trust = "trust-deny".equals(scenario) ? """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                 "Principal":{"Service":"lambda.amazonaws.com"}}]}
                """ : """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                 "Principal":{"AWS":"arn:aws:iam::%s:root"},
                 "Condition":{"ArnEquals":{"aws:PrincipalArn":"%s"}}}]}
                """.formatted(SOURCE, executionRole);
        String taskRole = role(TARGET, trust, """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Action":"states:*","Resource":"*"}]}
                """.formatted("start-deny".equals(scenario) ? "Deny" : "Allow"));
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Child", "States", Map.of("Child", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Credentials", Map.of("RoleArn", taskRole),
                "Parameters", Map.of("StateMachineArn", child, "Input", Map.of()), "End", true))));
        return new Fixture(machine(SOURCE, definition, "STANDARD", executionRole), child, executionRole, taskRole);
    }

    private String role(String account, String trust, String policy) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), account, "/",
                "nested-iam-" + UUID.randomUUID(), trust, policy);
    }

    private String machine(String account, String definition, String type, String role) {
        return sfn().createMachine(account, "nested-iam-", definition, type, role);
    }

    private String start(String machine) {
        return sfn().start(SOURCE, machine, "{}");
    }

    private Response terminal(String execution) throws Exception {
        return sfn().terminal(SOURCE, execution, 5);
    }

    private Response request(String account, String action, Map<String, ?> input) {
        return sfn().request(account, action, input);
    }

    private StepFunctionsTestClient sfn() {
        return new StepFunctionsTestClient(baseUri.getPort(), cleanup);
    }

    private record Fixture(String parent, String child, String executionRole, String taskRole) {
    }
}
