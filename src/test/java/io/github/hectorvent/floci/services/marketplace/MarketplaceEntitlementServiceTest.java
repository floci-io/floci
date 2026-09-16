package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketplaceEntitlementServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<JsonNode> storage;
    private MarketplaceEntitlementService service;

    @BeforeEach
    void setUp() {
        storage = AccountAwareStorageBackend.inMemory("000000000000");
        service = new MarketplaceEntitlementService(mapper, storage);
    }

    @Test
    void getEntitlementsReadsSharedReadOnlyStateAndAppliesFilters() throws Exception {
        storage.put("product-local/ent-1", mapper.readTree("""
                {"ProductCode":"product-local","Dimension":"users","CustomerAWSAccountId":"123456789012",
                "LicenseArn":"arn:aws:license-manager:us-east-1:123456789012:license:l-local",
                "Value":{"IntegerValue":10}}
                """));
        storage.put("product-local/ent-2", mapper.readTree("""
                {"ProductCode":"product-local","Dimension":"storage","CustomerAWSAccountId":"999999999999",
                "Value":{"IntegerValue":5}}
                """));

        JsonNode response = service.getEntitlements(mapper.readTree("""
                {"ProductCode":"product-local","Filter":{"CUSTOMER_AWS_ACCOUNT_ID":["123456789012"]}}
                """), "us-east-1");
        assertEquals(1, response.path("Entitlements").size());
        assertEquals("users", response.path("Entitlements").get(0).path("Dimension").asText());
        assertEquals("123456789012",
                response.path("Entitlements").get(0).path("CustomerAWSAccountId").asText());
    }

    @Test
    void maxResultsMustBeIntegral() throws Exception {
        AwsException error = assertThrows(AwsException.class,
                () -> service.getEntitlements(mapper.readTree(
                        "{\"ProductCode\":\"product-local\",\"MaxResults\":1.5}"), "us-east-1"));
        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void maxResultsRejectsIntegralOverflow() throws Exception {
        AwsException error = assertThrows(AwsException.class,
                () -> service.getEntitlements(mapper.readTree(
                        "{\"ProductCode\":\"product-local\",\"MaxResults\":4294967297}"), "us-east-1"));
        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void productCodeAndRegionFollowAwsConstraints() throws Exception {
        assertThrows(AwsException.class,
                () -> service.getEntitlements(mapper.readTree("{\"ProductCode\":\"\"}"), "us-east-1"));
        assertThrows(AwsException.class,
                () -> service.getEntitlements(mapper.readTree("{\"ProductCode\":\"product-local\"}"), "us-west-2"));
    }
}
