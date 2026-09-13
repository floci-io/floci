package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketplaceMeteringServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MarketplaceMeteringService service;

    @BeforeEach
    void setUp() {
        service = new MarketplaceMeteringService(mapper, null,
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"));
    }

    @Test
    void registerUsageReturnsPs256Jwt() throws Exception {
        String jwt = service.handle("RegisterUsage", mapper.readTree(
                "{\"ProductCode\":\"prod-local\",\"PublicKeyVersion\":1}"), "us-east-1")
                .path("Signature").asText();
        String header = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
        assertEquals("PS256", mapper.readTree(header).path("alg").asText());
        assertEquals(256, Base64.getUrlDecoder().decode(jwt.split("\\.")[2]).length);
    }

    @Test
    void meteringStateIsRegionScoped() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;
        JsonNode request = mapper.readTree("{\"ProductCode\":\"prod-local\",\"Timestamp\":" + timestamp
                + ",\"UsageDimension\":\"requests\",\"UsageQuantity\":1}");
        String east = service.handle("MeterUsage", request, "us-east-1").path("MeteringRecordId").asText();
        String west = service.handle("MeterUsage", request, "us-west-2").path("MeteringRecordId").asText();
        assertNotEquals(east, west);
    }


    @Test
    void genericValidationUsesValidationException() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;

        AwsException quantity = assertThrows(AwsException.class, () -> service.handle("MeterUsage", mapper.readTree(
                "{\"ProductCode\":\"prod-local\",\"Timestamp\":" + timestamp
                        + ",\"UsageDimension\":\"requests\",\"UsageQuantity\":1.5}"), "us-east-1"));
        assertEquals("ValidationException", quantity.getErrorCode());

        AwsException records = assertThrows(AwsException.class, () -> service.handle("BatchMeterUsage", mapper.readTree(
                "{\"ProductCode\":\"prod-local\",\"UsageRecords\":\"not-an-array\"}"), "us-east-1"));
        assertEquals("ValidationException", records.getErrorCode());

        AwsException clientToken = assertThrows(AwsException.class, () -> service.handle("MeterUsage", mapper.readTree(
                "{\"ProductCode\":\"prod-local\",\"Timestamp\":" + timestamp
                        + ",\"UsageDimension\":\"requests\",\"UsageQuantity\":1,\"ClientToken\":\""
                        + "x".repeat(65) + "\"}"), "us-east-1"));
        assertEquals("ValidationException", clientToken.getErrorCode());

        AwsException nonce = assertThrows(AwsException.class, () -> service.handle("RegisterUsage", mapper.readTree(
                "{\"ProductCode\":\"prod-local\",\"PublicKeyVersion\":1,\"Nonce\":\""
                        + "x".repeat(256) + "\"}"), "us-east-1"));
        assertEquals("ValidationException", nonce.getErrorCode());
    }

    @Test
    void fractionalQuantityAndBlankProductCodeAreRejected() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;
        assertThrows(AwsException.class, () -> service.handle("MeterUsage", mapper.readTree(
                "{\"ProductCode\":\"prod-local\",\"Timestamp\":" + timestamp
                        + ",\"UsageDimension\":\"requests\",\"UsageQuantity\":1.5}"), "us-east-1"));
        assertThrows(AwsException.class, () -> service.handle("RegisterUsage", mapper.readTree(
                "{\"ProductCode\":\"\",\"PublicKeyVersion\":1}"), "us-east-1"));
    }
}
