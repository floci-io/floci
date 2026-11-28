package io.floci.aws.services.appsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppSyncJsResolverDockerJsonTest {

    @Test
    void carriageReturnResolverCodeRemainsValidJson() throws Exception {
        String code = AppSyncJsResolverDockerNightlyIntegrationTest.COMMENT_CR_RESOLVER;
        String request = "{\"code\":\"" + AppSyncJsResolverDockerNightlyIntegrationTest.escape(code) + "\"}";

        assertEquals(code, new ObjectMapper().readTree(request).get("code").asText());
    }
}
