package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalCorsFilterTest {

    // Deployed API Gateway stages own their CORS — the global filter must skip them (#1928).
    @ParameterizedTest
    @CsvSource({
            "/execute-api/abc123/prod/graphql",
            "/execute-api/abc123/prod/",
            "/_aws/execute-api/abc123/prod/graphql",
            "/restapis/abc123/prod/_user_request_/graphql",
            "/restapis/abc123/prod/_user_request_",
            // tolerant of the leading-slash-less form UriInfo.getPath() can return
            "execute-api/abc123/prod/graphql",
    })
    void deployedApiPathsAreExcludedFromGlobalCors(String path) {
        assertTrue(GlobalCorsFilter.isDeployedApiPath(path));
    }

    // Management API and everything else keep Floci's global CORS handling.
    @ParameterizedTest
    @CsvSource({
            "/restapis/abc123/resources/xyz/methods/GET",
            "/restapis/abc123/deployments",
            "/restapis",
            "/my-bucket/key.txt",
            "/",
            "/_health",
    })
    void managementAndOtherPathsKeepGlobalCors(String path) {
        assertFalse(GlobalCorsFilter.isDeployedApiPath(path));
    }

    @Test
    void nullPathIsNotDeployedApi() {
        assertFalse(GlobalCorsFilter.isDeployedApiPath(null));
    }
}
