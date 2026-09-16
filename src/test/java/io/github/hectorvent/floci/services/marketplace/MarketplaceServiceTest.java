package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MarketplaceService service;

    @BeforeEach
    void setUp() {
        service = new MarketplaceService(
                mapper,
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                null);
    }

    @Test
    void startChangeSetRejectsUnsupportedRegion() throws Exception {
        JsonNode request = mapper.readTree("""
                {"Catalog":"AWSMarketplace","ChangeSet":[{"ChangeType":"CreateProduct",
                "Entity":{"Type":"SaaSProduct@1.0","Identifier":"@1"},"DetailsDocument":{}}]}
                """);
        AwsException error = assertThrows(AwsException.class,
                () -> service.startChangeSet(request, "us-west-2"));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void idempotentRetryRequiresSameParameters() throws Exception {
        JsonNode first = mapper.readTree("""
                {"Catalog":"AWSMarketplace","ClientRequestToken":"token-1",
                "ChangeSet":[{"ChangeType":"CreateProduct","Entity":{"Type":"SaaSProduct@1.0","Identifier":"@1"},
                "DetailsDocument":{"ProductTitle":"first"}}]}
                """);
        JsonNode different = mapper.readTree("""
                {"Catalog":"AWSMarketplace","ClientRequestToken":"token-1",
                "ChangeSet":[{"ChangeType":"CreateProduct","Entity":{"Type":"SaaSProduct@1.0","Identifier":"@1"},
                "DetailsDocument":{"ProductTitle":"different"}}]}
                """);
        String firstId = service.startChangeSet(first, "us-east-1").path("ChangeSetId").asText();
        assertEquals(firstId, service.startChangeSet(first, "us-east-1").path("ChangeSetId").asText());
        assertThrows(AwsException.class, () -> service.startChangeSet(different, "us-east-1"));
    }

    @Test
    void listEntitiesAppliesIdFilterAndAwsDefaultSort() throws Exception {
        String first = createProduct("Zeta");
        String second = createProduct("Alpha");
        JsonNode filtered = mapper.readTree("""
                {"Catalog":"AWSMarketplace","EntityType":"SaaSProduct",
                "FilterList":[{"Name":"EntityId","ValueList":["%s"]}]}
                """.formatted(first));
        JsonNode response = service.listEntities(filtered, "us-east-1");
        assertEquals(1, response.path("EntitySummaryList").size());
        assertEquals(first, response.path("EntitySummaryList").get(0).path("EntityId").asText());
        assertNotEquals(first, second);
    }

    @Test
    void listChangeSetsAppliesStatusFilter() throws Exception {
        createProduct("One");
        JsonNode request = mapper.readTree("""
                {"Catalog":"AWSMarketplace","FilterList":[{"Name":"Status","ValueList":["SUCCEEDED"]}]}
                """);
        JsonNode response = service.listChangeSets(request, "us-east-1");
        assertTrue(response.path("ChangeSetSummaryList").size() >= 1);
        assertEquals("SUCCEEDED", response.path("ChangeSetSummaryList").get(0).path("Status").asText());
    }

    @Test
    void malformedChangeSetEntryReturnsValidationException() throws Exception {
        JsonNode request = mapper.readTree("{\"Catalog\":\"AWSMarketplace\",\"ChangeSet\":[\"bad\"]}");
        AwsException error = assertThrows(AwsException.class,
                () -> service.startChangeSet(request, "us-east-1"));
        assertEquals("ValidationException", error.getErrorCode());
    }

    private String createProduct(String title) throws Exception {
        JsonNode request = mapper.readTree("""
                {"Catalog":"AWSMarketplace","ChangeSet":[{"ChangeType":"CreateProduct",
                "Entity":{"Type":"SaaSProduct@1.0","Identifier":"@1"},
                "DetailsDocument":{"ProductTitle":"%s"}}]}
                """.formatted(title));
        String changeSetId = service.startChangeSet(request, "us-east-1").path("ChangeSetId").asText();
        return service.describeChangeSet("AWSMarketplace", changeSetId, "us-east-1")
                .path("ChangeSet").get(0).path("Entity").path("Identifier").asText();
    }
}
