package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ssm.model.Command;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class SsmShellExecutionBudgetIntegrationTest extends SsmCommandIntegrationFixture {
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-west-2";
    @Inject ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"0,3600", "5,5", "172800,172800"})
    void publicResponseAndActualAgentPayloadKeepSeparateBudgets(int supplied, int execution) throws Exception {
        String instance = "i-budget-" + UUID.randomUUID();
        ObjectNode request = request(instance);
        if (supplied != 0) {
            request.withObject("Parameters").putArray("executionTimeout").add(Integer.toString(supplied));
        }
        try {
            Response response = call("AmazonSSM.SendCommand", request);
            assertEquals(200, response.statusCode());
            String id = response.jsonPath().getString("Command.CommandId");
            assertNotNull(id);
            Response poll = call("AmazonSSMMessageDeliveryService.GetMessages", mapper.valueToTree(
                    Map.of("Destination", instance, "MessagesRequestId", "budget")));
            assertEquals(200, poll.statusCode());
            assertEquals(1, poll.jsonPath().getList("Messages").size());
            JsonNode payload = mapper.readTree(Base64.getDecoder().decode(
                    poll.jsonPath().getString("Messages[0].Payload")));
            JsonNode command = mapper.readTree(response.asByteArray()).path("Command");
            assertAll("public metadata and delivered document",
                    () -> assertEquals(30, command.path("TimeoutSeconds").asInt()),
                    () -> assertEquals(30 + execution,
                            command.path("ExpiresAfter").asDouble() - command.path("RequestedDateTime").asDouble(),
                            0.001),
                    () -> assertEquals("3600", payload.at("/DocumentContent/parameters/executionTimeout/default")
                            .asText()),
                    () -> {
                        if (supplied != 0) {
                            assertEquals(Integer.toString(supplied),
                                    payload.at("/Parameters/executionTimeout/0").asText());
                        }
                    });
        } finally {
            cleanup(instance);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]", "[\"0\"]", "[\"-1\"]", "[\"172801\"]", "[\"999999999999999999999\"]",
            "[\"01\"]", "[\"+5\"]", "[\" 5\"]", "[\"5.0\"]", "[\"5\",\"6\"]",
            "null", "\"5\"", "[5]", "[null]", "{}"
    })
    void malformedExplicitBudgetCannotTurnIntoTheDefault(String json) throws Exception {
        String instance = "i-budget-" + UUID.randomUUID();
        ObjectNode request = request(instance);
        request.withObject("Parameters").set("executionTimeout", mapper.readTree(json));
        try {
            Response response = call("AmazonSSM.SendCommand", request);
            List<Command> commands = RequestScopes.callAs(ACCOUNT, REGION,
                    () -> commandService.listCommands(null, instance, REGION));
            assertAll("refuse before any allocation or dispatch",
                    () -> assertEquals(400, response.statusCode()),
                    () -> assertEquals("InvalidParameters", response.jsonPath().getString("__type")),
                    () -> assertTrue(commands.isEmpty()),
                    () -> verifyNoInteractions(executor));
            request.withObject("Parameters").putArray("executionTimeout").add("5");
            Response retry = call("AmazonSSM.SendCommand", request);
            assertEquals(200, retry.statusCode());
            JsonNode command = mapper.readTree(retry.asByteArray()).path("Command");
            assertEquals(35, command.path("ExpiresAfter").asDouble()
                    - command.path("RequestedDateTime").asDouble(), 0.001);
        } finally {
            cleanup(instance);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"bad\":\"shape\"}", "5", "null"})
    void malformedSiblingCannotEraseAValidExplicitBudget(String commands) throws Exception {
        String instance = "i-budget-" + UUID.randomUUID();
        ObjectNode request = request(instance);
        request.withObject("Parameters").putArray("executionTimeout").add("5");
        request.withObject("Parameters").set("commands", mapper.readTree(commands));
        try {
            Response response = call("AmazonSSM.SendCommand", request);
            assertAll("parameters conversion must not discard the explicit limit",
                    () -> assertEquals(400, response.statusCode()),
                    () -> assertEquals("InvalidParameters", response.jsonPath().getString("__type")),
                    () -> assertTrue(RequestScopes.callAs(ACCOUNT, REGION,
                            () -> commandService.listCommands(null, instance, REGION)).isEmpty()),
                    () -> verifyNoInteractions(executor));
        } finally {
            cleanup(instance);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "{}", "{\"commands\":[]}"})
    void shellRequiresAtLeastOneCommandBeforeAllocation(String parameters) throws Exception {
        String instance = "i-budget-" + UUID.randomUUID();
        ObjectNode request = request(instance);
        if ("missing".equals(parameters)) {
            request.remove("Parameters");
        } else {
            request.set("Parameters", mapper.readTree(parameters));
        }
        try {
            Response response = call("AmazonSSM.SendCommand", request);
            assertAll("required script is checked before allocation or dispatch",
                    () -> assertEquals(400, response.statusCode()),
                    () -> assertEquals("InvalidParameters", response.jsonPath().getString("__type")),
                    () -> assertTrue(RequestScopes.callAs(ACCOUNT, REGION,
                            () -> commandService.listCommands(null, instance, REGION)).isEmpty()),
                    () -> verifyNoInteractions(executor));
            Response retry = call("AmazonSSM.SendCommand", request(instance));
            assertEquals(200, retry.statusCode());
        } finally {
            cleanup(instance);
        }
    }

    private ObjectNode request(String instance) {
        ObjectNode request = mapper.createObjectNode();
        request.put("DocumentName", "AWS-RunShellScript");
        request.putArray("InstanceIds").add(instance);
        request.put("TimeoutSeconds", 30);
        request.putObject("Parameters").putArray("commands").add("echo budget");
        return request;
    }

    private Response call(String target, JsonNode request) {
        return super.call(ACCOUNT, REGION, target, request.toString());
    }

    private void cleanup(String instance) {
        cleanupCommands(ACCOUNT, REGION, instance);
    }
}
