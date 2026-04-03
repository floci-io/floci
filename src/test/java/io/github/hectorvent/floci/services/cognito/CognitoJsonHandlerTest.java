package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CognitoJsonHandlerTest {

    private CognitoJsonHandler handler;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        CognitoService service = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "http://localhost:4566",
                regionResolver
        );
        handler = new CognitoJsonHandler(service, mapper);
    }

    @Test
    void createUserPoolReturnsRichResponse() {
        ObjectNode request = mapper.createObjectNode();
        request.put("PoolName", "test-pool");
        ArrayNode schema = request.putArray("Schema");
        ObjectNode attr = schema.addObject();
        attr.put("Name", "email");
        attr.put("AttributeDataType", "String");

        Response response = handler.handle("CreateUserPool", request, "us-east-1");
        assertEquals(200, response.getStatus());

        JsonNode body = (JsonNode) response.getEntity();
        JsonNode pool = body.get("UserPool");

        assertNotNull(pool.get("Id"));
        assertEquals("test-pool", pool.get("Name").asText());
        assertTrue(pool.get("Arn").asText().contains("arn:aws:cognito-idp:us-east-1:000000000000:userpool/"));
        assertEquals("Enabled", pool.get("Status").asText());
        
        // Check mandatory blocks for Terraform
        assertNotNull(pool.get("SchemaAttributes"));
        assertEquals(1, pool.get("SchemaAttributes").size());
        assertEquals("email", pool.get("SchemaAttributes").get(0).get("Name").asText());

        assertNotNull(pool.get("Policies"));
        assertNotNull(pool.get("LambdaConfig"));
        assertNotNull(pool.get("AdminCreateUserConfig"));
        assertNotNull(pool.get("AccountRecoverySetting"));
        assertEquals("ESSENTIALS", pool.get("UserPoolTier").asText());
    }
}
