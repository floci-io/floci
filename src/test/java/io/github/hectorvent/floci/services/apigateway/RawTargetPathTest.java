package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void stagelessPathDropsThePrefixOfEveryExecuteApiUrlForm() {
        assertEquals("/a%20b/c", ApiGatewayExecuteController.rawStagelessPath("/a b/c", "/execute-api/api1/prod/a%20b/c"));
        assertEquals("/a%20b/c", ApiGatewayExecuteController.rawStagelessPath("/a b/c", "/_aws/execute-api/api1/prod/a%20b/c"));
        assertEquals("/a%20b/c", ApiGatewayExecuteController.rawStagelessPath("/a b/c", "/restapis/api1/prod/_user_request_/a%20b/c"));
    }

    @Test
    void stagelessPathOfTheStageRootIsTheRootPath() {
        assertEquals("/", ApiGatewayExecuteController.rawStagelessPath("/", "/execute-api/api1/prod"));
        assertEquals("/", ApiGatewayExecuteController.rawStagelessPath("/", "/execute-api/api1/prod/"));
        assertEquals("/", ApiGatewayExecuteController.rawStagelessPath("/", "/restapis/api1/prod/_user_request_"));
    }

    @Test
    void stagelessPathDropsATrailingSlashOnlyWhenTheNormalizedPathLostIt() {
        assertEquals("/x", ApiGatewayExecuteController.rawStagelessPath("/x", "/execute-api/api1/prod/x/"));
        assertEquals("/x/", ApiGatewayExecuteController.rawStagelessPath("/x/", "/execute-api/api1/prod/x/"));
    }

    @Test
    void stagelessPathOfAnUnknownUrlFormIsNull() {
        assertNull(ApiGatewayExecuteController.rawStagelessPath("/x", "/other/api1/prod/x"));
        assertNull(ApiGatewayExecuteController.rawStagelessPath("/x", null));
    }
}
