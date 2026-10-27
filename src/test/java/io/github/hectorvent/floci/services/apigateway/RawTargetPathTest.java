package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawTargetPathTest {

    @Test
    void encodedCharactersAndTrailingSlashSurviveAfterTheGatewayPrefix() {
        assertEquals("/a%3Fb", ApiGatewayExecuteController.rawTargetPath("/a?b", "/execute-api/api1/$default/a%3Fb"));
        assertEquals("/x/", ApiGatewayExecuteController.rawTargetPath("/x", "/execute-api/api1/prod/x/"));
        assertEquals("/a%20b/c", ApiGatewayExecuteController.rawTargetPath("/a b/c", "/execute-api/api1/prod/a%20b/c"));
    }

    @Test
    void emptyRemainderIsTheRootPath() {
        assertEquals("/", ApiGatewayExecuteController.rawTargetPath("/", "/execute-api/api1/prod"));
        assertEquals("/", ApiGatewayExecuteController.rawTargetPath("/", "/execute-api/api1/prod/"));
    }

    @Test
    void missingRawPathFallsBackToTheNormalizedPath() {
        assertEquals("/x", ApiGatewayExecuteController.rawTargetPath("/x", null));
    }
}
