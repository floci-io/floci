package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the wire-accurate empty-list responses for the read-only Glue actions on resources
 * the emulator does not model (GetJobs, GetCrawlers, ListDataQualityRulesets, GetSecurityConfigurations).
 * Each must return HTTP 200, an empty list under its result key, and omit NextToken.
 */
class GlueJsonHandlerEmptyListTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    private GlueJsonHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        GlueSchemaRegistryService schemaRegistryService =
                new GlueSchemaRegistryService(storageFactory, regionResolver);
        GlueService glueService = new GlueService(
                storageFactory, schemaRegistryService, regionResolver, new ResourceGroupsTaggingService(storageFactory));
        handler = new GlueJsonHandler(glueService, schemaRegistryService, mapper);
    }

    @Test
    void getJobsReturnsEmptyJobsList() throws Exception {
        assertEmptyList("GetJobs", "Jobs");
    }

    @Test
    void getCrawlersReturnsEmptyCrawlersList() throws Exception {
        assertEmptyList("GetCrawlers", "Crawlers");
    }

    @Test
    void listDataQualityRulesetsReturnsEmptyRulesetsList() throws Exception {
        assertEmptyList("ListDataQualityRulesets", "Rulesets");
    }

    @Test
    void getSecurityConfigurationsReturnsEmptyList() throws Exception {
        assertEmptyList("GetSecurityConfigurations", "SecurityConfigurations");
    }

    private void assertEmptyList(String action, String listKey) throws Exception {
        Response response = handler.handle(action, mapper.createObjectNode(), REGION);

        assertEquals(200, response.getStatus());

        JsonNode body = mapper.valueToTree(response.getEntity());
        assertTrue(body.has(listKey), action + " response must contain " + listKey);
        assertTrue(body.get(listKey).isArray(), listKey + " must be a JSON array");
        assertEquals(0, body.get(listKey).size(), listKey + " must be empty");
        assertFalse(body.has("NextToken"), action + " response must omit NextToken");
        assertEquals(1, body.size(), action + " response must contain only " + listKey);
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> StorageBackend<String, V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return new InMemoryStorage<>();
        }
    }
}
