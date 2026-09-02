package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    private GlueJsonHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        GlueSchemaRegistryService schemaRegistryService =
                new GlueSchemaRegistryService(storageFactory, regionResolver);
        GlueService glueService = new GlueService(
                storageFactory, schemaRegistryService, regionResolver, new ResourceGroupsTaggingService(storageFactory));
        handler = new GlueJsonHandler(glueService, schemaRegistryService, mapper);
    }

    @Test
    void createCrawlerWithScheduleSucceeds() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", "test-crawler");
        request.put("Role", "arn:aws:iam::000000000000:role/role");
        request.put("Schedule", "cron(15 12 * * ? *)");
        
        ObjectNode targets = request.putObject("Targets");
        targets.putArray("S3Targets").addObject().put("Path", "s3://bucket/path/");

        Response response = handler.handle("CreateCrawler", request, REGION);
        assertEquals(200, response.getStatus());

        JsonNode body = mapper.valueToTree(response.getEntity());
        assertNotNull(body);

        Response getResponse = handler.handle("GetCrawler", mapper.createObjectNode().put("Name", "test-crawler"), REGION);
        assertEquals(200, getResponse.getStatus());
        JsonNode getBody = mapper.valueToTree(getResponse.getEntity());
        assertTrue(getBody.has("Crawler"));
        JsonNode crawler = getBody.get("Crawler");
        assertEquals("test-crawler", crawler.get("Name").asText());
        assertEquals("READY", crawler.get("State").asText());
        assertEquals(1, crawler.get("Version").asInt());
        assertTrue(crawler.has("Schedule"));
        assertEquals("cron(15 12 * * ? *)", crawler.get("Schedule").get("ScheduleExpression").asText());
    }

    @Test
    void updateCrawlerWithScheduleSucceeds() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", "test-crawler-update");
        request.put("Role", "arn:aws:iam::000000000000:role/role");
        
        ObjectNode targets = request.putObject("Targets");
        targets.putArray("S3Targets").addObject().put("Path", "s3://bucket/path/");

        assertEquals(200, handler.handle("CreateCrawler", request, REGION).getStatus());

        ObjectNode updateRequest = mapper.createObjectNode();
        updateRequest.put("Name", "test-crawler-update");
        updateRequest.put("Schedule", "cron(0 0 * * ? *)");

        Response response = handler.handle("UpdateCrawler", updateRequest, REGION);
        assertEquals(200, response.getStatus());

        Response getResponse = handler.handle("GetCrawler", mapper.createObjectNode().put("Name", "test-crawler-update"), REGION);
        assertEquals(200, getResponse.getStatus());
        JsonNode getBody = mapper.valueToTree(getResponse.getEntity());
        JsonNode crawler = getBody.get("Crawler");
        assertEquals("test-crawler-update", crawler.get("Name").asText());
        assertEquals(2, crawler.get("Version").asInt());
        assertTrue(crawler.has("Schedule"));
        assertEquals("cron(0 0 * * ? *)", crawler.get("Schedule").get("ScheduleExpression").asText());
    }

    @Test
    void createJobSucceeds() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", "test-job");
        request.put("Role", "arn:aws:iam::000000000000:role/role");
        
        ObjectNode command = request.putObject("Command");
        command.put("Name", "glueetl");
        command.put("ScriptLocation", "s3://bucket/script.py");

        Response response = handler.handle("CreateJob", request, REGION);
        assertEquals(200, response.getStatus());

        Response getResponse = handler.handle("GetJob", mapper.createObjectNode().put("JobName", "test-job"), REGION);
        assertEquals(200, getResponse.getStatus());
        JsonNode getBody = mapper.valueToTree(getResponse.getEntity());
        assertTrue(getBody.has("Job"));
        JsonNode job = getBody.get("Job");
        assertEquals("test-job", job.get("Name").asText());
        assertEquals("glueetl", job.get("Command").get("Name").asText());
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
