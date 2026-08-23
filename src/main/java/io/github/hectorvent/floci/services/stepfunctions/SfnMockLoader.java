package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedResponseStep;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedTestCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the Step Functions Local compatible mock configuration file
 * ({@code MockConfigFile.json}) referenced by {@code SFN_MOCK_CONFIG} or
 * {@code floci.services.stepfunctions.mock-config-file}. The file is re-read when its
 * modification time changes, so it can be edited without restarting the emulator.
 */
@ApplicationScoped
public class SfnMockLoader {

    private static final Logger LOG = Logger.getLogger(SfnMockLoader.class);

    private final Optional<String> configuredPath;
    private final ObjectMapper objectMapper;
    private volatile CachedMockFile cache;

    private record CachedMockFile(String path, long lastModified, JsonNode root) {
    }

    @Inject
    public SfnMockLoader(EmulatorConfig config, ObjectMapper objectMapper) {
        this(config.services().stepfunctions().mockConfigFile(), objectMapper);
    }

    SfnMockLoader(Optional<String> configuredPath, ObjectMapper objectMapper) {
        this.configuredPath = configuredPath.filter(path -> !path.isBlank());
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves a test case selected via {@code StartExecution} on
     * {@code <stateMachineArn>#<testCaseName>}. Throws {@code ValidationException} when no
     * mock configuration is configured or the test case cannot be resolved from it.
     */
    public MockedTestCase requireTestCase(String stateMachineName, String testCaseName) {
        var path = configuredPath.orElseThrow(() -> new AwsException("ValidationException",
                "Cannot run test case '" + testCaseName + "': no mock configuration file is configured. "
                        + "Set SFN_MOCK_CONFIG to the path of a mock configuration file.", 400));
        var root = load(path);
        var testCases = root.path("StateMachines").path(stateMachineName).path("TestCases");
        if (!testCases.isObject()) {
            throw new AwsException("ValidationException",
                    "State machine '" + stateMachineName + "' has no test cases in mock configuration file "
                            + path, 400);
        }
        var testCase = testCases.path(testCaseName);
        if (!testCase.isObject()) {
            throw new AwsException("ValidationException",
                    "Test case '" + testCaseName + "' is not defined for state machine '" + stateMachineName
                            + "' in mock configuration file " + path, 400);
        }
        var mockedResponses = root.path("MockedResponses");
        var stateResponses = new LinkedHashMap<String, List<MockedResponseStep>>();
        testCase.fields().forEachRemaining(entry -> {
            var stateName = entry.getKey();
            var responseKey = entry.getValue();
            if (!responseKey.isTextual()) {
                throw new AwsException("ValidationException",
                        "Test case '" + testCaseName + "' state '" + stateName
                                + "' must reference a MockedResponses entry by name", 400);
            }
            var responseNode = mockedResponses.path(responseKey.asText());
            if (!responseNode.isObject()) {
                throw new AwsException("ValidationException",
                        "Mocked response '" + responseKey.asText() + "' referenced by test case '"
                                + testCaseName + "' state '" + stateName + "' is not defined", 400);
            }
            stateResponses.put(stateName, parseSteps(responseKey.asText(), responseNode));
        });
        LOG.debugv("Resolved mock test case {0}#{1} covering states {2}",
                stateMachineName, testCaseName, stateResponses.keySet());
        return new MockedTestCase(stateMachineName, testCaseName, Map.copyOf(stateResponses));
    }

    private JsonNode load(String path) {
        var file = Path.of(path);
        long lastModified;
        try {
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new AwsException("ValidationException",
                    "Mock configuration file not found: " + path, 400);
        }
        var cached = cache;
        if (cached != null && cached.path().equals(path) && cached.lastModified() == lastModified) {
            return cached.root();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new AwsException("ValidationException",
                    "Failed to read mock configuration file " + path + ": " + e.getMessage(), 400);
        }
        if (root == null || !root.isObject()) {
            throw new AwsException("ValidationException",
                    "Mock configuration file " + path + " must contain a JSON object", 400);
        }
        cache = new CachedMockFile(path, lastModified, root);
        return root;
    }

    private List<MockedResponseStep> parseSteps(String responseKey, JsonNode responseNode) {
        var steps = new ArrayList<MockedResponseStep>();
        responseNode.fields().forEachRemaining(
                entry -> steps.add(parseStep(responseKey, entry.getKey(), entry.getValue())));
        if (steps.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Mocked response '" + responseKey + "' must define at least one attempt entry", 400);
        }
        steps.sort(Comparator.comparingInt(MockedResponseStep::fromAttempt));
        return List.copyOf(steps);
    }

    private MockedResponseStep parseStep(String responseKey, String attemptRange, JsonNode step) {
        var range = parseAttemptRange(responseKey, attemptRange);
        var returnNode = step.get("Return");
        var throwNode = step.get("Throw");
        if ((returnNode == null) == (throwNode == null)) {
            throw new AwsException("ValidationException",
                    "Mocked response '" + responseKey + "' attempt '" + attemptRange
                            + "' must contain exactly one of Return or Throw", 400);
        }
        if (returnNode != null) {
            return new MockedResponseStep(range[0], range[1], returnNode, null, null);
        }
        var error = throwNode.path("Error").asText(null);
        if (error == null || error.isBlank()) {
            throw new AwsException("ValidationException",
                    "Mocked response '" + responseKey + "' attempt '" + attemptRange
                            + "' Throw must contain a non-empty Error", 400);
        }
        return new MockedResponseStep(range[0], range[1], null, error, throwNode.path("Cause").asText(""));
    }

    private int[] parseAttemptRange(String responseKey, String attemptRange) {
        try {
            var separator = attemptRange.indexOf('-');
            int from;
            int to;
            if (separator < 0) {
                from = Integer.parseInt(attemptRange.trim());
                to = from;
            } else {
                from = Integer.parseInt(attemptRange.substring(0, separator).trim());
                to = Integer.parseInt(attemptRange.substring(separator + 1).trim());
            }
            if (from < 0 || to < from) {
                throw new NumberFormatException(attemptRange);
            }
            return new int[] {from, to};
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException",
                    "Mocked response '" + responseKey + "' has an invalid attempt key '" + attemptRange
                            + "'; expected a number like \"0\" or a range like \"1-2\"", 400);
        }
    }
}
