package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawTargetPathTest {

    @Test
    void namedStageStaysInTheTargetPathAsTheCallerEncodedIt() {
        assertEquals("/prod/a%3Fb", ApiGatewayExecuteController.rawTargetPath("/a?b", "/execute-api/api1/prod/a%3Fb", "prod"));
        assertEquals("/prod/x/", ApiGatewayExecuteController.rawTargetPath("/x", "/execute-api/api1/prod/x/", "prod"));
        assertEquals("/test/a%20b/c", ApiGatewayExecuteController.rawTargetPath("/a b/c", "/execute-api/api1/test/a%20b/c", "test"));
    }

    @Test
    void defaultStageIsNotPartOfTheTargetPath() {
        assertEquals("/a%3Fb", ApiGatewayExecuteController.rawTargetPath("/a?b", "/execute-api/api1/$default/a%3Fb", "$default"));
        assertEquals("/x/", ApiGatewayExecuteController.rawTargetPath("/x", "/execute-api/api1/$default/x/", "$default"));
    }

    @Test
    void emptyRemainderIsTheStageRootOrTheRootPath() {
        assertEquals("/prod", ApiGatewayExecuteController.rawTargetPath("/", "/execute-api/api1/prod", "prod"));
        assertEquals("/prod/", ApiGatewayExecuteController.rawTargetPath("/", "/execute-api/api1/prod/", "prod"));
        assertEquals("/", ApiGatewayExecuteController.rawTargetPath("/", "/execute-api/api1/$default", "$default"));
        assertEquals("/", ApiGatewayExecuteController.rawTargetPath("/", "/execute-api/api1/$default/", "$default"));
    }

    @Test
    void missingRawPathFallsBackToTheNormalizedPath() {
        assertEquals("/x", ApiGatewayExecuteController.rawTargetPath("/x", null, "$default"));
        assertEquals("/prod/x", ApiGatewayExecuteController.rawTargetPath("/x", null, "prod"));
    }
}
