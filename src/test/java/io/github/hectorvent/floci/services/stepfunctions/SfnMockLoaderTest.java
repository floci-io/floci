package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedResponseStep;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        Path file = tempDir.resolve("mock-config.json");
        Files.writeString(file, content);
        return new SfnMockLoader(Optional.of(file.toString()), new ObjectMapper());
    }

    @Test
    void parsesReturnAndThrowStepsWithAttemptRanges() throws IOException {
        MockedTestCase testCase = loader(CONFIG).requireTestCase("Test", "Case");

        assertEquals("Case", testCase.testCaseName());
        List<MockedResponseStep> steps = testCase.stateResponses().get("Call API");
        assertEquals(2, steps.size());

        MockedResponseStep throwStep = steps.get(0);
        assertTrue(throwStep.isThrow());
        assertTrue(throwStep.covers(0));
        assertFalse(throwStep.covers(1));
        assertEquals("ApiGateway.422", throwStep.errorName());
        assertEquals("Unprocessable", throwStep.errorCause());

        MockedResponseStep returnStep = steps.get(1);
        assertFalse(returnStep.isThrow());
        assertTrue(returnStep.covers(1));
        assertTrue(returnStep.covers(2));
        assertFalse(returnStep.covers(3));
        assertEquals(200, returnStep.returnResult().path("StatusCode").asInt());
        assertNull(returnStep.errorName());
    }

    @Test
    void rejectsUnknownStateMachine() throws IOException {
        SfnMockLoader loader = loader(CONFIG);
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Other", "Case"));
        assertTrue(e.getMessage().contains("Other"));
    }

    @Test
    void rejectsUnknownTestCase() throws IOException {
        SfnMockLoader loader = loader(CONFIG);
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Missing"));
        assertTrue(e.getMessage().contains("Missing"));
    }

    @Test
    void rejectsMissingMockedResponsesEntry() throws IOException {
        SfnMockLoader loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "Nope" } } }
                  },
                  "MockedResponses": {}
                }
                """);
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("Nope"));
    }

    @Test
    void rejectsInvalidAttemptKey() throws IOException {
        SfnMockLoader loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "Bad" } } }
                  },
                  "MockedResponses": {
                    "Bad": { "first": { "Return": {} } }
                  }
                }
                """);
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("first"));
    }

    @Test
    void rejectsStepWithBothReturnAndThrow() throws IOException {
        SfnMockLoader loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "Both" } } }
                  },
                  "MockedResponses": {
                    "Both": { "0": { "Return": {}, "Throw": { "Error": "X" } } }
                  }
                }
                """);
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("exactly one of Return or Throw"));
    }

    @Test
    void rejectsThrowWithoutError() throws IOException {
        SfnMockLoader loader = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Call API": "NoError" } } }
                  },
                  "MockedResponses": {
                    "NoError": { "0": { "Throw": { "Cause": "no name" } } }
                  }
                }
                """);
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("Error"));
    }

    @Test
    void acceptsMockedResponseWithNoAttemptEntries() throws IOException {
        MockedTestCase testCase = loader("""
                {
                  "StateMachines": {
                    "Test": { "TestCases": { "Case": { "Skipped": "Empty", "Call API": "Ok" } } }
                  },
                  "MockedResponses": {
                    "Empty": {},
                    "Ok": { "0": { "Return": { "StatusCode": 200 } } }
                  }
                }
                """).requireTestCase("Test", "Case");

        assertTrue(testCase.stateResponses().get("Skipped").isEmpty());
        assertEquals(1, testCase.stateResponses().get("Call API").size());
    }

    @Test
    void reportsMissingFile() {
        SfnMockLoader loader = new SfnMockLoader(
                Optional.of(tempDir.resolve("absent.json").toString()), new ObjectMapper());
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void reportsUnconfiguredLoader() {
        SfnMockLoader loader = new SfnMockLoader(Optional.empty(), new ObjectMapper());
        AwsException e = assertThrows(AwsException.class, () -> loader.requireTestCase("Test", "Case"));
        assertTrue(e.getMessage().contains("SFN_MOCK_CONFIG"));
    }

    @Test
    void reloadsFileWhenModified() throws IOException, InterruptedException {
        Path file = tempDir.resolve("mock-config.json");
        Files.writeString(file, CONFIG);
        SfnMockLoader loader = new SfnMockLoader(Optional.of(file.toString()), new ObjectMapper());
        assertEquals(2, loader.requireTestCase("Test", "Case").stateResponses().get("Call API").size());

        Thread.sleep(1100);
        Files.writeString(file, CONFIG.replace("\"1-2\"", "\"1\""));
        List<MockedResponseStep> steps = loader.requireTestCase("Test", "Case").stateResponses().get("Call API");
        assertEquals(1, steps.get(1).toAttempt());
    }
}
