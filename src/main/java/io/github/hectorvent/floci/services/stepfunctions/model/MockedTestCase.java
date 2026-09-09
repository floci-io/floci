package io.github.hectorvent.floci.services.stepfunctions.model;

import java.util.List;
import java.util.Map;

/**
 * A resolved mock test case for one execution: state name to the attempt-keyed mocked
 * responses that replace that Task state's service call. States not present here run
 * their real integration.
 */
public record MockedTestCase(
        String stateMachineName,
        String testCaseName,
        Map<String, List<MockedResponseStep>> stateResponses) {
}
