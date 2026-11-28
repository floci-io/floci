package io.floci.aws.services.cloudwatch.dashboards;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.dashboards.model.Dashboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchDashboardsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String BODY = "{\"widgets\":[{\"type\":\"metric\",\"width\":6}]}";

    private CloudWatchDashboardsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchDashboardsService(
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000")
        );
    }

    @Test
    void putAndGetReturnsBodyVerbatim() {
        service.putDashboard("ops", BODY, REGION);

        Dashboard dashboard = service.getDashboard("ops", REGION);
        assertEquals(BODY, dashboard.getDashboardBody());
        assertEquals("ops", dashboard.getDashboardName());
        assertEquals("arn:aws:cloudwatch::000000000000:dashboard/ops", dashboard.getDashboardArn());
    }

    // Catches: a dashboard ARN minted with the request's Region; AWS's has none, in every partition.
    @Test
    void theDashboardArnIsRegionlessInTheRegionsPartition() {
        service.putDashboard("ops", BODY, "cn-north-1");

        assertEquals("arn:aws-cn:cloudwatch::000000000000:dashboard/ops",
                service.getDashboard("ops", "cn-north-1").getDashboardArn());
    }

    // Catches: a dashboard persisted before the change reading back with its old region-ful ARN.
    @Test
    void aDashboardStoredWithTheOldRegionfulArnReadsBackRegionless() {
        InMemoryStorage<String, Dashboard> store = new InMemoryStorage<>();
        store.put(REGION + "::legacy", new Dashboard("legacy",
                "arn:aws:cloudwatch:us-east-1:000000000000:dashboard/legacy", BODY));
        CloudWatchDashboardsService legacy = new CloudWatchDashboardsService(store,
                new RegionResolver(REGION, "000000000000"));

        String regionless = "arn:aws:cloudwatch::000000000000:dashboard/legacy";
        assertEquals(regionless, legacy.getDashboard("legacy", REGION).getDashboardArn());
        assertEquals(regionless, legacy.listDashboards(null, REGION).get(0).getDashboardArn());
        legacy.tagResource(regionless, Map.of("env", "dev"), REGION);
        assertEquals(Map.of("env", "dev"), legacy.listTagsForResource(regionless, REGION));
    }

    @Test
    void putOnExistingNameOverwrites() {
        service.putDashboard("ops", BODY, REGION);
        service.putDashboard("ops", "{\"widgets\":[]}", REGION);

        assertEquals("{\"widgets\":[]}", service.getDashboard("ops", REGION).getDashboardBody());
        assertEquals(1, service.listDashboards(null, REGION).size());
    }

    @Test
    void getMissingDashboardThrowsResourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.getDashboard("nope", REGION));
        assertEquals("ResourceNotFound", e.getErrorCode());
    }

    @Test
    void putRejectsNullBody() {
        assertThrows(AwsException.class, () -> service.putDashboard("ops", null, REGION));
    }

    @Test
    void putRejectsAMalformedBody() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.putDashboard("ops", "{\"widgets\": [", REGION));
        assertEquals("InvalidParameterInput", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    /** A leading object followed by anything else is still a malformed document, not a dashboard. */
    @Test
    void putRejectsABodyWithTrailingContentAfterTheObject() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.putDashboard("ops", "{\"widgets\": []} and then some", REGION));
        assertEquals("InvalidParameterInput", e.getErrorCode());
    }

    /** A body that parses but is not an object is equally unusable as a dashboard. */
    @Test
    void putRejectsABodyThatIsNotAJsonObject() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.putDashboard("ops", "[]", REGION));
        assertEquals("InvalidParameterInput", e.getErrorCode());
    }

    @Test
    void listDashboardsFiltersByNamePrefix() {
        service.putDashboard("prod-api", BODY, REGION);
        service.putDashboard("prod-web", BODY, REGION);
        service.putDashboard("staging-api", BODY, REGION);

        List<Dashboard> filtered = service.listDashboards("prod-", REGION);
        assertEquals(List.of("prod-api", "prod-web"),
                filtered.stream().map(Dashboard::getDashboardName).toList());
        assertEquals(3, service.listDashboards(null, REGION).size());
    }

    // Catches: dashboards kept per Region; AWS keeps them global per account.
    @Test
    void aDashboardPutInOneRegionIsTheSameDashboardInEveryRegion() {
        service.putDashboard("ops", BODY, REGION);
        service.putDashboard("ops", "{\"widgets\":[]}", "eu-west-1");

        assertEquals(1, service.listDashboards(null, REGION).size());
        assertEquals("{\"widgets\":[]}", service.getDashboard("ops", REGION).getDashboardBody());

        service.deleteDashboards(List.of("ops"), "ap-southeast-2");
        assertTrue(service.listDashboards(null, "eu-west-1").isEmpty());
    }

    @Test
    void deleteDashboardsRemovesEveryNamedDashboard() {
        service.putDashboard("a", BODY, REGION);
        service.putDashboard("b", BODY, REGION);
        service.putDashboard("c", BODY, REGION);

        service.deleteDashboards(List.of("a", "b"), REGION);

        assertEquals(List.of("c"),
                service.listDashboards(null, REGION).stream().map(Dashboard::getDashboardName).toList());
    }

    @Test
    void tagsGivenOnCreateAreReadableByArn() {
        service.putDashboard("ops", BODY, Map.of("team", "platform"), REGION);
        String arn = service.getDashboard("ops", REGION).getDashboardArn();

        assertEquals(Map.of("team", "platform"), service.listTagsForResource(arn, REGION));
        assertTrue(CloudWatchDashboardsService.isDashboardArn(arn));
    }

    /**
     * AWS documents Tags on PutDashboard as create-only, so replacing a dashboard keeps the tags
     * it already had: an update must not be able to retag a resource sideways.
     */
    @Test
    void tagsOnAReplacingPutDoNotOverwriteTheExistingOnes() {
        service.putDashboard("ops", BODY, Map.of("team", "platform"), REGION);
        String arn = service.getDashboard("ops", REGION).getDashboardArn();

        service.putDashboard("ops", BODY, Map.of("team", "someone-else"), REGION);

        assertEquals(Map.of("team", "platform"), service.listTagsForResource(arn, REGION));
    }

    @Test
    void tagAndUntagResourceReachDashboards() {
        service.putDashboard("ops", BODY, REGION);
        String arn = service.getDashboard("ops", REGION).getDashboardArn();

        service.tagResource(arn, Map.of("env", "dev"), REGION);
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(arn, REGION));

        service.untagResource(arn, List.of("env"), REGION);
        assertEquals(Map.of(), service.listTagsForResource(arn, REGION));
    }

    /**
     * Floci mints the regionless ARN AWS uses, and used to mint one with the request's Region.
     * Callers may still hold the old form, so both resolve to the same dashboard.
     */
    @Test
    void bothTheRegionlessAndTheOldRegionfulDashboardArnResolve() {
        service.putDashboard("ops", BODY, REGION);
        String regionless = service.getDashboard("ops", REGION).getDashboardArn();
        String regionful = "arn:aws:cloudwatch:us-east-1:000000000000:dashboard/ops";

        assertEquals("arn:aws:cloudwatch::000000000000:dashboard/ops", regionless);
        assertTrue(CloudWatchDashboardsService.isDashboardArn(regionless));

        service.tagResource(regionful, Map.of("env", "dev"), REGION);
        // Read back through the other form: one dashboard, reachable either way.
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(regionless, REGION));
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(regionful, REGION));

        service.untagResource(regionful, List.of("env"), REGION);
        assertEquals(Map.of(), service.listTagsForResource(regionless, REGION));
    }

    /** A dashboard is global per account, so an old region-ful ARN naming any Region resolves. */
    @Test
    void anOldRegionfulArnNamingAnotherRegionResolves() {
        service.putDashboard("ops", BODY, REGION);
        String elsewhere = "arn:aws:cloudwatch:eu-west-1:000000000000:dashboard/ops";

        service.tagResource(elsewhere, Map.of("env", "dev"), REGION);
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(elsewhere, "eu-west-1"));
    }

    // Catches: dashboards persisted under the old region::name keys vanishing, or folding into
    // an arbitrary copy when one name was stored in two Regions.
    @Test
    void legacyRegionKeysFoldOntoTheNameKeepingTheMostRecentlyModifiedCopy() {
        InMemoryStorage<String, Dashboard> store = new InMemoryStorage<>();
        store.put("us-east-1::ops", legacyDashboard("ops", "us-east-1", "{\"v\":\"old\"}", 100));
        store.put("eu-west-1::ops", legacyDashboard("ops", "eu-west-1", "{\"v\":\"new\"}", 200));
        store.put("eu-west-1::solo", legacyDashboard("solo", "eu-west-1", BODY, 50));

        CloudWatchDashboardsService folded = new CloudWatchDashboardsService(store,
                new RegionResolver(REGION, "000000000000"));

        assertEquals(Set.of("ops", "solo"), store.keys());
        assertEquals("{\"v\":\"new\"}", folded.getDashboard("ops", "eu-west-1").getDashboardBody());
        assertEquals("arn:aws:cloudwatch::000000000000:dashboard/ops",
                folded.getDashboard("ops", "ap-southeast-2").getDashboardArn());
        assertEquals(List.of("ops", "solo"),
                folded.listDashboards(null, REGION).stream().map(Dashboard::getDashboardName).toList());
    }

    @Test
    void aLegacyTieGoesToTheRegionThatSortsFirst() {
        InMemoryStorage<String, Dashboard> store = new InMemoryStorage<>();
        store.put("us-west-2::ops", legacyDashboard("ops", "us-west-2", "{\"r\":\"us-west-2\"}", 100));
        store.put("ap-south-1::ops", legacyDashboard("ops", "ap-south-1", "{\"r\":\"ap-south-1\"}", 100));

        CloudWatchDashboardsService folded = new CloudWatchDashboardsService(store,
                new RegionResolver(REGION, "000000000000"));

        assertEquals("{\"r\":\"ap-south-1\"}", folded.getDashboard("ops", "eu-west-1").getDashboardBody());
    }

    @Test
    void legacyRegionKeysFoldInEveryAccount() {
        AccountAwareStorageBackend<Dashboard> store = AccountAwareStorageBackend.inMemory("000000000000");
        store.putForAccount("000000000000", "us-east-1::ops", legacyDashboard("ops", "us-east-1", BODY, 100));
        store.putForAccount("111111111111", "eu-west-1::ops", legacyDashboard("ops", "eu-west-1", BODY, 100));

        new CloudWatchDashboardsService(store, new RegionResolver(REGION, "000000000000"));

        assertTrue(store.getForAccount("000000000000", "ops").isPresent());
        assertTrue(store.getForAccount("111111111111", "ops").isPresent());
        assertTrue(store.getForAccount("000000000000", "us-east-1::ops").isEmpty());
        assertTrue(store.getForAccount("111111111111", "eu-west-1::ops").isEmpty());
    }

    private static Dashboard legacyDashboard(String name, String region, String body, long lastModified) {
        Dashboard dashboard = new Dashboard(name,
                "arn:aws:cloudwatch:" + region + ":000000000000:dashboard/" + name, body);
        dashboard.setLastModified(lastModified);
        return dashboard;
    }

    /**
     * An ARN naming no dashboard is an error on all three operations, not a silent no-op. The
     * ARN used here differs from a working one only in the dashboard name, so the outcome
     * cannot be explained by the ARN failing to parse as a dashboard ARN.
     */
    @Test
    void tagOperationsRejectAnArnThatNamesNoDashboard() {
        service.putDashboard("ops", BODY, REGION);
        String arn = service.getDashboard("ops", REGION).getDashboardArn();
        String ghost = arn.replace("dashboard/ops", "dashboard/nosuch");
        assertTrue(CloudWatchDashboardsService.isDashboardArn(ghost));

        for (Runnable call : List.<Runnable>of(
                () -> service.tagResource(ghost, Map.of("env", "prod"), REGION),
                () -> service.untagResource(ghost, List.of("env"), REGION),
                () -> service.listTagsForResource(ghost, REGION))) {
            AwsException e = assertThrows(AwsException.class, call::run);
            assertEquals("ResourceNotFoundException", e.getErrorCode());
            assertEquals(404, e.getHttpStatus());
        }

        // The failures wrote nothing: the real dashboard still carries only what it was given.
        assertEquals(Map.of(), service.listTagsForResource(arn, REGION));
        service.tagResource(arn, Map.of("env", "dev"), REGION);
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(arn, REGION));
    }

    @Test
    void deleteDashboardsIsBestEffortWhenOneNameIsMissing() {
        service.putDashboard("a", BODY, REGION);
        service.putDashboard("b", BODY, REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteDashboards(List.of("a", "missing", "b"), REGION));
        assertEquals("ResourceNotFound", e.getErrorCode());
        // AWS attempts to delete as many dashboards as possible, so the two that existed are
        // gone even though the batch errored, and a name after the missing one is still tried.
        assertEquals(0, service.listDashboards(null, REGION).size());
    }
}
