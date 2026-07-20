package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesisanalytics.container.FlinkContainerManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class KinesisAnalyticsV2JsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/x";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KinesisAnalyticsV2JsonHandler handler;

    @BeforeEach
    void setUp() {
        handler = new KinesisAnalyticsV2JsonHandler(mockModeService(), MAPPER);
    }

    private ObjectNode entity(Response response) {
        return (ObjectNode) response.getEntity();
    }

    private void createApplication(String name) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", name);
        req.put("RuntimeEnvironment", "FLINK-1_18");
        req.put("ServiceExecutionRole", ROLE);
        assertThat(handler.handle("CreateApplication", req, REGION).getStatus(), is(200));
    }

    @Test
    void createApplicationReturnsReadyDetail() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "demo");
        req.put("RuntimeEnvironment", "FLINK-1_18");
        req.put("ServiceExecutionRole", ROLE);

        Response resp = handler.handle("CreateApplication", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode detail = (ObjectNode) entity(resp).get("ApplicationDetail");
        assertEquals("demo", detail.get("ApplicationName").asText());
        assertEquals("READY", detail.get("ApplicationStatus").asText());
        assertEquals(1, detail.get("ApplicationVersionId").asLong());
        assertThat(detail.get("ApplicationARN").asText().contains(":kinesisanalytics:"), is(true));
    }

    @Test
    void describeApplicationReturnsDetail() {
        createApplication("demo");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "demo");
        Response resp = handler.handle("DescribeApplication", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode detail = (ObjectNode) entity(resp).get("ApplicationDetail");
        assertEquals("demo", detail.get("ApplicationName").asText());
    }

    @Test
    void startThenDescribeShowsRunning() {
        createApplication("demo");

        ObjectNode start = MAPPER.createObjectNode();
        start.put("ApplicationName", "demo");
        assertThat(handler.handle("StartApplication", start, REGION).getStatus(), is(200));

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("ApplicationName", "demo");
        ObjectNode detail = (ObjectNode) entity(handler.handle("DescribeApplication", describe, REGION))
                .get("ApplicationDetail");
        // Mock mode: the application comes up RUNNING immediately.
        assertEquals("RUNNING", detail.get("ApplicationStatus").asText());
    }

    @Test
    void listApplicationsIncludesCreated() {
        createApplication("demo-a");
        createApplication("demo-b");

        Response resp = handler.handle("ListApplications", MAPPER.createObjectNode(), REGION);
        assertThat(resp.getStatus(), is(200));
        var summaries = entity(resp).get("ApplicationSummaries");
        assertEquals(2, summaries.size());
    }

    @Test
    void deleteApplicationRemovesIt() {
        createApplication("demo");

        ObjectNode del = MAPPER.createObjectNode();
        del.put("ApplicationName", "demo");
        assertThat(handler.handle("DeleteApplication", del, REGION).getStatus(), is(200));

        var summaries = entity(handler.handle("ListApplications", MAPPER.createObjectNode(), REGION))
                .get("ApplicationSummaries");
        assertEquals(0, summaries.size());
    }

    @Test
    void unsupportedActionReturns400() {
        Response resp = handler.handle("BogusAction", MAPPER.createObjectNode(), REGION);
        assertThat(resp.getStatus(), is(400));
    }

    private KinesisAnalyticsV2Service mockModeService() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kaConfig);
        when(kaConfig.mock()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        return new KinesisAnalyticsV2Service(storageFactory, config, regionResolver,
                Mockito.mock(FlinkContainerManager.class));
    }
}
