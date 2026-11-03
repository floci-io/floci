package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppSyncJsResolverDockerJsonTest {

    @Test
    void carriageReturnResolverCodeRemainsValidJson() throws Exception {
        String code = AppSyncJsResolverDockerIntegrationTest.COMMENT_CR_RESOLVER;
        String request = "{\"code\":\"" + AppSyncJsResolverDockerIntegrationTest.escape(code) + "\"}";

        assertEquals(code, new ObjectMapper().readTree(request).get("code").asText());
    }
}
