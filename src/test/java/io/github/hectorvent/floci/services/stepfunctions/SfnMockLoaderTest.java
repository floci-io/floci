package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SfnMockLoaderTest {

    private static final String CONFIG = """
            {
              "StateMachines": {
                "Test": {
                  "TestCases": {
                    "Case": { "Call API": "ThrowThenOk" }
                  }
                }
              },
              "MockedResponses": {
                "ThrowThenOk": {
                  "0": { "Throw": { "Error": "ApiGateway.422", "Cause": "Unprocessable" } },
                  "1-2": { "Return": { "StatusCode": 200 } }
                }
              }
            }
            """;

    @TempDir
    Path tempDir;

    private SfnMockLoader loader(String content) throws IOException {
        var file = tempDir.resolve("mock-config.json");
        Files.writeString(file, content);
        return new SfnMockLoader(Optional.of(file.toString()), new ObjectMapper());
    }

    @Test
    void parsesReturnAndThrowStepsWithAttemptRanges() throws IOException {
        var testCase = loader(CONFIG).requireTestCase("Test", "Case");

        assertEquals("Case", testCase.testCaseName());
        var steps = testCase.stateResponses().get("Call API");
        assertEquals(2, steps.size());

        var throwStep = steps.get(0);
        assertTrue(throwStep.isThrow());
        assertTrue(throwStep.covers(0));
        assertFalse(throwStep.covers(1));
        assertEquals("ApiGateway.422", throwStep.errorName());
        assertEquals("Unprocessable", throwStep.errorCause());

        var returnStep = steps.get(1);
        assertFalse(returnStep.isThrow());
        assertTrue(returnStep.covers(1));
        assertTrue(returnStep.covers(2));
        assertFalse(returnStep.covers(3));
        assertEquals(200, returnStep.returnResult().path("StatusCode").asInt());
        assertNull(returnStep.errorName());
    }

    @Test
    void rejectsUnknownStateMachine() throws IOException {
        var loader = loader(CONFIG);
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Other", "Case"));
        assertTrue(e.getMessage().contains("Other"));
    }

    @Test
    void rejectsUnknownTestCase() throws IOException {
        var loader = loader(CONFIG);
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Missing"));
        assertTrue(e.getMessage().contains("Missing"));
    }

    @Test
    void rejectsMissingMockedResponsesEntry() throws IOException {
        var loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "Nope" } } }
                  },
                  "MockedResponses": {}
                }
                """);
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("Nope"));
    }

    @Test
    void rejectsInvalidAttemptKey() throws IOException {
        var loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "Bad" } } }
                  },
                  "MockedResponses": {
                    "Bad": { "first": { "Return": {} } }
                  }
                }
                """);
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("first"));
    }

    @Test
    void rejectsStepWithBothReturnAndThrow() throws IOException {
        var loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "Both" } } }
                  },
                  "MockedResponses": {
                    "Both": { "0": { "Return": {}, "Throw": { "Error": "X" } } }
                  }
                }
                """);
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("exactly one of Return or Throw"));
    }

    @Test
    void rejectsThrowWithoutError() throws IOException {
        var loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "NoError" } } }
                  },
                  "MockedResponses": {
                    "NoError": { "0": { "Throw": { "Cause": "no name" } } }
                  }
                }
                """);
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("Error"));
    }

    @Test
    void reportsMissingFile() {
        var loader = new SfnMockLoader(
                Optional.of(tempDir.resolve("absent.json").toString()), new ObjectMapper());
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void reportsUnconfiguredLoader() {
        var loader = new SfnMockLoader(Optional.empty(), new ObjectMapper());
        var e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("SFN_MOCK_CONFIG"));
    }

    @Test
    void reloadsFileWhenModified() throws IOException, InterruptedException {
        var file = tempDir.resolve("mock-config.json");
        Files.writeString(file, CONFIG);
        var loader = new SfnMockLoader(Optional.of(file.toString()), new ObjectMapper());
        assertEquals(2, loader.requireTestCase("Test", "Case").stateResponses().get("Call API").size());

        Thread.sleep(1100);
        Files.writeString(file, CONFIG.replace("\"1-2\"", "\"1\""));
        var steps = loader.requireTestCase("Test", "Case").stateResponses().get("Call API");
        assertEquals(1, steps.get(1).toAttempt());
    }
}
