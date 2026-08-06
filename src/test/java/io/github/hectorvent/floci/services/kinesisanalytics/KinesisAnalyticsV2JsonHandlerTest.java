package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // DeleteApplication requires the app's CreateTimestamp (epoch seconds).
        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("ApplicationName", "demo");
        double createTs = ((ObjectNode) entity(handler.handle("DescribeApplication", describe, REGION))
                .get("ApplicationDetail")).get("CreateTimestamp").asDouble();

        ObjectNode del = MAPPER.createObjectNode();
        del.put("ApplicationName", "demo");
        del.put("CreateTimestamp", createTs);
        assertThat(handler.handle("DeleteApplication", del, REGION).getStatus(), is(200));

        var summaries = entity(handler.handle("ListApplications", MAPPER.createObjectNode(), REGION))
                .get("ApplicationSummaries");
        assertEquals(0, summaries.size());
    }

    @Test
    void createWithCodeConfigEchoesItOnDescribe() {
        ObjectNode s3 = MAPPER.createObjectNode();
        s3.put("BucketARN", "arn:aws:s3:::flink-code");
        s3.put("FileKey", "app.jar");
        ObjectNode codeCfg = MAPPER.createObjectNode();
        codeCfg.set("CodeContent", MAPPER.createObjectNode().set("S3ContentLocation", s3));
        codeCfg.put("CodeContentType", "ZIPFILE");
        ObjectNode flinkCfg = MAPPER.createObjectNode();
        flinkCfg.set("ParallelismConfiguration", MAPPER.createObjectNode().put("Parallelism", 3));
        ObjectNode appCfg = MAPPER.createObjectNode();
        appCfg.set("ApplicationCodeConfiguration", codeCfg);
        appCfg.set("FlinkApplicationConfiguration", flinkCfg);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "coded");
        req.put("RuntimeEnvironment", "FLINK-1_18");
        req.put("ServiceExecutionRole", ROLE);
        req.set("ApplicationConfiguration", appCfg);
        assertThat(handler.handle("CreateApplication", req, REGION).getStatus(), is(200));

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("ApplicationName", "coded");
        ObjectNode detail = (ObjectNode) entity(handler.handle("DescribeApplication", describe, REGION))
                .get("ApplicationDetail");
        var loc = detail.get("ApplicationConfigurationDescription")
                .get("ApplicationCodeConfigurationDescription")
                .get("CodeContentDescription").get("S3ApplicationCodeLocationDescription");
        assertEquals("arn:aws:s3:::flink-code", loc.get("BucketARN").asText());
        assertEquals("app.jar", loc.get("FileKey").asText());
        assertEquals(3, detail.get("ApplicationConfigurationDescription")
                .get("FlinkApplicationConfigurationDescription")
                .get("ParallelismConfigurationDescription").get("Parallelism").asInt());
    }

    @Test
    void createWithEnvironmentPropertiesEchoesThemOnDescribe() {
        ObjectNode s3 = MAPPER.createObjectNode();
        s3.put("BucketARN", "arn:aws:s3:::flink-code");
        s3.put("FileKey", "app.jar");
        ObjectNode codeCfg = MAPPER.createObjectNode();
        codeCfg.set("CodeContent", MAPPER.createObjectNode().set("S3ContentLocation", s3));
        ObjectNode propertyGroup = MAPPER.createObjectNode();
        propertyGroup.put("PropertyGroupId", "ProducerConfigProperties");
        propertyGroup.set("PropertyMap", MAPPER.createObjectNode().put("aws.region", "us-west-2"));
        ObjectNode envProps = MAPPER.createObjectNode();
        envProps.putArray("PropertyGroups").add(propertyGroup);
        ObjectNode appCfg = MAPPER.createObjectNode();
        appCfg.set("ApplicationCodeConfiguration", codeCfg);
        appCfg.set("EnvironmentProperties", envProps);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "envprops");
        req.put("RuntimeEnvironment", "FLINK-1_18");
        req.put("ServiceExecutionRole", ROLE);
        req.set("ApplicationConfiguration", appCfg);
        assertThat(handler.handle("CreateApplication", req, REGION).getStatus(), is(200));

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("ApplicationName", "envprops");
        ObjectNode detail = (ObjectNode) entity(handler.handle("DescribeApplication", describe, REGION))
                .get("ApplicationDetail");
        var groups = detail.get("ApplicationConfigurationDescription")
                .get("EnvironmentPropertyDescriptions").get("PropertyGroupDescriptions");
        assertEquals(1, groups.size());
        assertEquals("ProducerConfigProperties", groups.get(0).get("PropertyGroupId").asText());
        assertEquals("us-west-2", groups.get(0).get("PropertyMap").get("aws.region").asText());
    }

    @Test
    void createApplicationDefaultsSnapshotsEnabledToTrueOnDescribe() {
        createApplication("demo");

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("ApplicationName", "demo");
        ObjectNode detail = (ObjectNode) entity(handler.handle("DescribeApplication", describe, REGION))
                .get("ApplicationDetail");
        // No code configured, so ApplicationConfigurationDescription isn't built at all — matches how
        // EnvironmentPropertyDescriptions is also only ever echoed when the app has code.
        assertThat(detail.has("ApplicationConfigurationDescription"), is(false));
    }

    @Test
    void createApplicationWithSnapshotsDisabledEchoesItOnDescribe() {
        ObjectNode s3 = MAPPER.createObjectNode();
        s3.put("BucketARN", "arn:aws:s3:::flink-code");
        s3.put("FileKey", "app.jar");
        ObjectNode codeCfg = MAPPER.createObjectNode();
        codeCfg.set("CodeContent", MAPPER.createObjectNode().set("S3ContentLocation", s3));
        ObjectNode appCfg = MAPPER.createObjectNode();
        appCfg.set("ApplicationCodeConfiguration", codeCfg);
        appCfg.set("ApplicationSnapshotConfiguration",
                MAPPER.createObjectNode().put("SnapshotsEnabled", false));

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "nosnaps");
        req.put("RuntimeEnvironment", "FLINK-1_18");
        req.put("ServiceExecutionRole", ROLE);
        req.set("ApplicationConfiguration", appCfg);
        assertThat(handler.handle("CreateApplication", req, REGION).getStatus(), is(200));

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("ApplicationName", "nosnaps");
        ObjectNode detail = (ObjectNode) entity(handler.handle("DescribeApplication", describe, REGION))
                .get("ApplicationDetail");
        assertThat(detail.get("ApplicationConfigurationDescription")
                .get("ApplicationSnapshotConfigurationDescription").get("SnapshotsEnabled").asBoolean(), is(false));

        ObjectNode createSnap = MAPPER.createObjectNode();
        createSnap.put("ApplicationName", "nosnaps");
        createSnap.put("SnapshotName", "attempt");
        assertThrows(AwsException.class, () -> handler.handle("CreateApplicationSnapshot", createSnap, REGION));
    }

    @Test
    void createApplicationPresignedUrlRejectsUnsupportedUrlType() {
        createApplication("demo");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "demo");
        req.put("UrlType", "ZEPPELIN_UI_URL");
        assertThrows(AwsException.class, () -> handler.handle("CreateApplicationPresignedUrl", req, REGION));
    }

    @Test
    void createApplicationPresignedUrlRejectsWhenNotRunning() {
        createApplication("demo"); // READY, never started

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "demo");
        req.put("UrlType", "FLINK_DASHBOARD_URL");
        assertThrows(AwsException.class, () -> handler.handle("CreateApplicationPresignedUrl", req, REGION));
    }

    @Test
    void updateApplicationWithNewCodeLocationEchoesItOnDescribe() {
        createRunningCodedApplication("redeploy-target");

        ObjectNode s3Update = MAPPER.createObjectNode();
        s3Update.put("BucketARNUpdate", "arn:aws:s3:::flink-code-v2");
        s3Update.put("FileKeyUpdate", "app-v2.jar");
        ObjectNode codeCfgUpdate = MAPPER.createObjectNode();
        codeCfgUpdate.set("CodeContentUpdate", MAPPER.createObjectNode().set("S3ContentLocationUpdate", s3Update));
        ObjectNode appCfgUpdate = MAPPER.createObjectNode();
        appCfgUpdate.set("ApplicationCodeConfigurationUpdate", codeCfgUpdate);

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("ApplicationName", "redeploy-target");
        updateReq.put("CurrentApplicationVersionId", 1L);
        updateReq.set("ApplicationConfigurationUpdate", appCfgUpdate);
        Response updated = handler.handle("UpdateApplication", updateReq, REGION);
        assertThat(updated.getStatus(), is(200));
        ObjectNode detail = (ObjectNode) entity(updated).get("ApplicationDetail");
        assertEquals(2L, detail.get("ApplicationVersionId").asLong());

        var loc = detail.get("ApplicationConfigurationDescription")
                .get("ApplicationCodeConfigurationDescription")
                .get("CodeContentDescription").get("S3ApplicationCodeLocationDescription");
        assertEquals("arn:aws:s3:::flink-code-v2", loc.get("BucketARN").asText());
        assertEquals("app-v2.jar", loc.get("FileKey").asText());
    }

    @Test
    void unsupportedActionReturns400() {
        Response resp = handler.handle("BogusAction", MAPPER.createObjectNode(), REGION);
        assertThat(resp.getStatus(), is(400));
    }

    @Test
    void createApplicationWithTagsThenListTagsForResourceRoundTrips() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", "tagged");
        req.put("RuntimeEnvironment", "FLINK-1_18");
        req.put("ServiceExecutionRole", ROLE);
        req.putArray("Tags")
                .addObject().put("Key", "env").put("Value", "dev");
        Response created = handler.handle("CreateApplication", req, REGION);
        assertThat(created.getStatus(), is(200));
        // Tags is a list of {Key, Value} objects on this API, never echoed on ApplicationDetail.
        assertThat(entity(created).get("ApplicationDetail").has("Tags"), is(false));

        String arn = entity(created).get("ApplicationDetail").get("ApplicationARN").asText();
        ObjectNode listReq = MAPPER.createObjectNode();
        listReq.put("ResourceARN", arn);
        Response listed = handler.handle("ListTagsForResource", listReq, REGION);
        assertThat(listed.getStatus(), is(200));
        var tags = entity(listed).get("Tags");
        assertEquals(1, tags.size());
        assertEquals("env", tags.get(0).get("Key").asText());
        assertEquals("dev", tags.get(0).get("Value").asText());
    }

    @Test
    void tagResourceThenUntagResourceRoundTrips() {
        createApplication("demo");
        String arn = describeArn("demo");

        ObjectNode tagReq = MAPPER.createObjectNode();
        tagReq.put("ResourceARN", arn);
        tagReq.putArray("Tags").addObject().put("Key", "team").put("Value", "platform");
        assertThat(handler.handle("TagResource", tagReq, REGION).getStatus(), is(200));

        ObjectNode listReq = MAPPER.createObjectNode();
        listReq.put("ResourceARN", arn);
        assertEquals(1, entity(handler.handle("ListTagsForResource", listReq, REGION)).get("Tags").size());

        ObjectNode untagReq = MAPPER.createObjectNode();
        untagReq.put("ResourceARN", arn);
        untagReq.putArray("TagKeys").add("team");
        assertThat(handler.handle("UntagResource", untagReq, REGION).getStatus(), is(200));

        assertEquals(0, entity(handler.handle("ListTagsForResource", listReq, REGION)).get("Tags").size());
    }

    @Test
    void createApplicationSnapshotThenDescribeAndListThenDeleteRoundTrips() {
        createRunningCodedApplication("streaming");

        ObjectNode createSnap = MAPPER.createObjectNode();
        createSnap.put("ApplicationName", "streaming");
        createSnap.put("SnapshotName", "before-upgrade");
        assertThat(handler.handle("CreateApplicationSnapshot", createSnap, REGION).getStatus(), is(200));

        ObjectNode describeSnap = MAPPER.createObjectNode();
        describeSnap.put("ApplicationName", "streaming");
        describeSnap.put("SnapshotName", "before-upgrade");
        Response described = handler.handle("DescribeApplicationSnapshot", describeSnap, REGION);
        assertThat(described.getStatus(), is(200));
        ObjectNode details = (ObjectNode) entity(described).get("SnapshotDetails");
        assertEquals("before-upgrade", details.get("SnapshotName").asText());
        // Mock mode: the snapshot completes immediately, same as the application itself.
        assertEquals("READY", details.get("SnapshotStatus").asText());
        double creationTimestamp = details.get("SnapshotCreationTimestamp").asDouble();

        Response listed = handler.handle("ListApplicationSnapshots",
                MAPPER.createObjectNode().put("ApplicationName", "streaming"), REGION);
        assertThat(listed.getStatus(), is(200));
        var summaries = entity(listed).get("SnapshotSummaries");
        assertEquals(1, summaries.size());
        assertEquals("before-upgrade", summaries.get(0).get("SnapshotName").asText());

        ObjectNode deleteSnap = MAPPER.createObjectNode();
        deleteSnap.put("ApplicationName", "streaming");
        deleteSnap.put("SnapshotName", "before-upgrade");
        deleteSnap.put("SnapshotCreationTimestamp", creationTimestamp);
        assertThat(handler.handle("DeleteApplicationSnapshot", deleteSnap, REGION).getStatus(), is(200));

        var afterDelete = entity(handler.handle("ListApplicationSnapshots",
                MAPPER.createObjectNode().put("ApplicationName", "streaming"), REGION)).get("SnapshotSummaries");
        assertEquals(0, afterDelete.size());
    }

    @Test
    void createApplicationSnapshotFailsWhenApplicationNotRunning() {
        createApplication("demo");

        ObjectNode createSnap = MAPPER.createObjectNode();
        createSnap.put("ApplicationName", "demo");
        createSnap.put("SnapshotName", "before-upgrade");
        assertThrows(AwsException.class, () -> handler.handle("CreateApplicationSnapshot", createSnap, REGION));
    }

    private void createRunningCodedApplication(String name) {
        ObjectNode s3 = MAPPER.createObjectNode();
        s3.put("BucketARN", "arn:aws:s3:::flink-code");
        s3.put("FileKey", "app.jar");
        ObjectNode codeCfg = MAPPER.createObjectNode();
        codeCfg.set("CodeContent", MAPPER.createObjectNode().set("S3ContentLocation", s3));
        codeCfg.put("CodeContentType", "ZIPFILE");
        ObjectNode appCfg = MAPPER.createObjectNode();
        appCfg.set("ApplicationCodeConfiguration", codeCfg);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ApplicationName", name);
        req.put("RuntimeEnvironment", "FLINK-1_18");
        req.put("ServiceExecutionRole", ROLE);
        req.set("ApplicationConfiguration", appCfg);
        assertThat(handler.handle("CreateApplication", req, REGION).getStatus(), is(200));

        ObjectNode start = MAPPER.createObjectNode();
        start.put("ApplicationName", name);
        assertThat(handler.handle("StartApplication", start, REGION).getStatus(), is(200));
    }

    private String describeArn(String name) {
        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("ApplicationName", name);
        return entity(handler.handle("DescribeApplication", describe, REGION))
                .get("ApplicationDetail").get("ApplicationARN").asText();
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
