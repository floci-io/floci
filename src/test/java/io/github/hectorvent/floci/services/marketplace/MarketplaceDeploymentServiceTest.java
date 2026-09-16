package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketplaceDeploymentServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<JsonNode> parameters;
    private MarketplaceDeploymentService service;

    @BeforeEach
    void setUp() {
        parameters = AccountAwareStorageBackend.inMemory("000000000000");
        service = new MarketplaceDeploymentService(
                mapper, parameters, AccountAwareStorageBackend.inMemory("000000000000"));
    }

    @Test
    void clientTokenRejectsChangedPayload() throws Exception {
        String token = "12345678901234567890123456789012";
        JsonNode first = mapper.readTree("{\"agreementId\":\"agr-1\",\"clientToken\":\"" + token
                + "\",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"one\"}}");
        JsonNode changed = mapper.readTree("{\"agreementId\":\"agr-1\",\"clientToken\":\"" + token
                + "\",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"two\"}}");
        service.putDeploymentParameter("AWSMarketplace", "prod-1", first, "us-east-1", "000000000000");
        assertThrows(AwsException.class, () -> service.putDeploymentParameter(
                "AWSMarketplace", "prod-1", changed, "us-east-1", "000000000000"));
    }

    @Test
    void expiredParameterGetsNewIdentity() throws Exception {
        JsonNode expired = mapper.readTree("{\"agreementId\":\"agr-1\",\"expirationDate\":"
                + (Instant.now().minusSeconds(60).toEpochMilli() / 1000.0)
                + ",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"one\"}}");
        String oldId = service.putDeploymentParameter("AWSMarketplace", "prod-1", expired,
                "us-east-1", "000000000000").path("deploymentParameterId").asText();
        JsonNode fresh = mapper.readTree("{\"agreementId\":\"agr-1\",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"two\"}}");
        String newId = service.putDeploymentParameter("AWSMarketplace", "prod-1", fresh,
                "us-east-1", "000000000000").path("deploymentParameterId").asText();
        assertNotEquals(oldId, newId);
    }

    @Test
    void expiredParameterInvalidatesCachedClientTokenReplay() throws Exception {
        String token = "12345678901234567890123456789012";
        JsonNode request = mapper.readTree("{\"agreementId\":\"agr-1\",\"clientToken\":\"" + token
                + "\",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"one\"}}");
        String oldId = service.putDeploymentParameter("AWSMarketplace", "prod-1", request,
                "us-east-1", "000000000000").path("deploymentParameterId").asText();

        String key = "AWSMarketplace/prod-1/agr-1/ApiKey";
        ObjectNode stored = (ObjectNode) parameters.get(key).orElseThrow().deepCopy();
        stored.put("expirationDate", Instant.now().minusSeconds(60).toString());
        parameters.put(key, stored);

        String newId = service.putDeploymentParameter("AWSMarketplace", "prod-1", request,
                "us-east-1", "000000000000").path("deploymentParameterId").asText();

        assertNotEquals(oldId, newId);
    }

    @Test
    void tagOperationsRejectWrongRegion() throws Exception {
        JsonNode request = mapper.readTree("{\"agreementId\":\"agr-1\",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"one\"}}");
        String arn = service.putDeploymentParameter("AWSMarketplace", "prod-1", request,
                "us-east-1", "000000000000").path("resourceArn").asText();
        AwsException error = assertThrows(AwsException.class,
                () -> service.listTagsForResource(arn, "us-west-2"));
        assertEquals("ValidationException", error.getErrorCode());
    }
}
