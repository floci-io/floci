package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchDashboardsTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BODY = """
            {"widgets":[{"type":"metric","x":0,"y":0,"width":12,"height":6,\
            "properties":{"metrics":[["AWS/EC2","CPUUtilization"]],"region":"us-east-1"}}]}""";

    private CloudWatchMetricsService service;
    private CloudWatchMetricsQueryHandler queryHandler;
    private CloudWatchMetricsJsonHandler jsonHandler;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"),
                MAPPER
        );
        queryHandler = new CloudWatchMetricsQueryHandler(service);
        jsonHandler = new CloudWatchMetricsJsonHandler(service, MAPPER);
    }

    private MultivaluedMap<String, String> putParams(String name, String body) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("DashboardName", name);
        params.add("DashboardBody", body);
        return params;
    }

    @Test
    void putDashboardReturnsAnEmptyValidationMessageList() {
        Response response = queryHandler.handle("PutDashboard", putParams("ops", BODY), REGION);
        String xml = (String) response.getEntity();

        assertTrue(xml.contains("<PutDashboardResult>"));
        assertTrue(xml.contains("<DashboardValidationMessages></DashboardValidationMessages>"));
    }

    @Test
    void getDashboardReturnsTheExactBodyThatWasPut() {
        queryHandler.handle("PutDashboard", putParams("ops", BODY), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("DashboardName", "ops");
        String xml = (String) queryHandler.handle("GetDashboard", params, REGION).getEntity();

        assertTrue(xml.contains("<DashboardName>ops</DashboardName>"));
        assertTrue(xml.contains("arn:aws:cloudwatch::000000000000:dashboard/ops"));
        assertEquals(BODY, service.getDashboard("ops", REGION).getBody());
    }

    @Test
    void listDashboardsReturnsEveryDashboardWithItsSize() {
        queryHandler.handle("PutDashboard", putParams("ops", BODY), REGION);
        queryHandler.handle("PutDashboard", putParams("billing", BODY), REGION);

        String xml = (String) queryHandler.handle("ListDashboards", new MultivaluedHashMap<>(), REGION).getEntity();

        assertTrue(xml.contains("<DashboardEntries>"));
        assertTrue(xml.contains("<DashboardName>ops</DashboardName>"));
        assertTrue(xml.contains("<DashboardName>billing</DashboardName>"));
        assertTrue(xml.contains("<Size>" + BODY.length() + "</Size>"));
    }

    @Test
    void listDashboardsHonoursTheNamePrefix() {
        queryHandler.handle("PutDashboard", putParams("ops", BODY), REGION);
        queryHandler.handle("PutDashboard", putParams("billing", BODY), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("DashboardNamePrefix", "bil");
        String xml = (String) queryHandler.handle("ListDashboards", params, REGION).getEntity();

        assertTrue(xml.contains("<DashboardName>billing</DashboardName>"));
        assertTrue(!xml.contains("<DashboardName>ops</DashboardName>"));
    }

    @Test
    void putDashboardReplacesTheStoredBody() {
        queryHandler.handle("PutDashboard", putParams("ops", BODY), REGION);
        queryHandler.handle("PutDashboard", putParams("ops", "{\"widgets\":[]}"), REGION);

        assertEquals("{\"widgets\":[]}", service.getDashboard("ops", REGION).getBody());
    }

    @Test
    void deleteDashboardsRemovesThem() {
        queryHandler.handle("PutDashboard", putParams("ops", BODY), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("DashboardNames.member.1", "ops");
        queryHandler.handle("DeleteDashboards", params, REGION);

        assertTrue(service.listDashboards(null, REGION).isEmpty());
    }

    @Test
    void getUnknownDashboardThrowsResourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.getDashboard("absent", REGION));
        assertEquals("ResourceNotFound", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void deleteUnknownDashboardThrowsResourceNotFound() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("DashboardNames.member.1", "absent");
        AwsException e = assertThrows(AwsException.class,
                () -> queryHandler.handle("DeleteDashboards", params, REGION));
        assertEquals("ResourceNotFound", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void jsonHandlerRoundTripsADashboard() throws Exception {
        JsonNode putRequest = MAPPER.createObjectNode()
                .put("DashboardName", "json-dash")
                .put("DashboardBody", BODY);
        JsonNode put = MAPPER.valueToTree(jsonHandler.handle("PutDashboard", putRequest, REGION).getEntity());
        assertEquals(0, put.path("DashboardValidationMessages").size());

        JsonNode get = MAPPER.valueToTree(jsonHandler.handle("GetDashboard",
                MAPPER.readTree("{\"DashboardName\": \"json-dash\"}"), REGION).getEntity());
        assertEquals("json-dash", get.path("DashboardName").asText());
        assertEquals(BODY, get.path("DashboardBody").asText());
        assertEquals("arn:aws:cloudwatch::000000000000:dashboard/json-dash", get.path("DashboardArn").asText());

        JsonNode list = MAPPER.valueToTree(jsonHandler.handle("ListDashboards",
                MAPPER.createObjectNode(), REGION).getEntity());
        assertEquals(1, list.path("DashboardEntries").size());
        assertEquals(BODY.length(), list.path("DashboardEntries").get(0).path("Size").asLong());

        jsonHandler.handle("DeleteDashboards", MAPPER.readTree("{\"DashboardNames\": [\"json-dash\"]}"), REGION);
        assertTrue(service.listDashboards(null, REGION).isEmpty());
    }
}
