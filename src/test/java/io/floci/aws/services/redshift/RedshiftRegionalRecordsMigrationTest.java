package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.container.RedshiftContainerManager;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.ClusterParameterGroup;
import io.floci.aws.services.redshift.model.ClusterSubnetGroup;
import io.floci.aws.services.redshift.model.Parameter;
import io.floci.aws.services.redshift.model.SnapshotCopyGrant;
import io.floci.aws.services.redshift.model.SnapshotSchedule;
import io.floci.aws.services.redshift.proxy.RedshiftProxyManager;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.redshift.RedshiftService.regionalKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Cluster parameter groups, subnet groups, snapshot schedules and snapshot copy grants were stored under their name
 * alone and seen from every Region. At startup each moves to the Region it shows it was made in (a subnet group's
 * subnets' Region, a copy grant's KMS key's), or else to the default Region, and is copied to every other Region
 * where one of its account's clusters uses it: a cluster's parameter group, subnet group and schedule in the
 * cluster's Region, its copy grant in the copy's destination Region.
 */
class RedshiftRegionalRecordsMigrationTest {

    private static final String ACCOUNT = "111111111111";

    @TempDir
    Path directory;

    private EmulatorConfig config;
    private StorageFactory factory;
    private AccountAwareStorageBackend<ClusterParameterGroup> groups;
    private AccountAwareStorageBackend<ClusterSubnetGroup> subnets;
    private AccountAwareStorageBackend<SnapshotSchedule> schedules;
    private AccountAwareStorageBackend<SnapshotCopyGrant> grants;
    private AccountAwareStorageBackend<Cluster> clusters;

    /** Opens the service's stores in the test's directory; each test fills them as an older Floci stored them. */
    @BeforeEach
    void openStores() {
        config = StorageFactories.config(directory, ACCOUNT);
        factory = StorageFactories.open(config, "persistent");
        groups = factory.create("redshift", "redshift-parameter-groups.json",
                new TypeReference<Map<String, ClusterParameterGroup>>() { });
        subnets = factory.create("redshift", "redshift-subnet-groups.json",
                new TypeReference<Map<String, ClusterSubnetGroup>>() { });
        schedules = factory.create("redshift", "redshift-snapshot-schedules.json",
                new TypeReference<Map<String, SnapshotSchedule>>() { });
        grants = factory.create("redshift", "redshift-snapshot-copy-grants.json",
                new TypeReference<Map<String, SnapshotCopyGrant>>() { });
        clusters = factory.create("redshift", "redshift-clusters.json",
                new TypeReference<Map<String, Cluster>>() { });
    }

    @AfterEach
    void closeStores() {
        factory.shutdownAll();
    }

    @Test
    void recordsStoredWithoutARegionMoveToTheDefaultRegionAndToEachRegionWhoseClustersUseThem() {
        groups.putForAccount(ACCOUNT, "analytics", new ClusterParameterGroup("analytics", "redshift-1.0", "d"));
        subnets.putForAccount(ACCOUNT, "private", new ClusterSubnetGroup("private", "d", null, List.of()));
        schedules.putForAccount(ACCOUNT, "nightly", new SnapshotSchedule("nightly", "d", List.of("rate(12 hours)")));
        grants.putForAccount(ACCOUNT, "copies", new SnapshotCopyGrant("copies", "key"));
        Cluster west = pausedCluster("west", "eu-west-1");
        west.setClusterParameterGroupName("analytics");
        west.setSnapshotScheduleIdentifier("nightly");
        clusters.putForAccount(ACCOUNT, "west", west);
        Cluster east = pausedCluster("east", null);
        east.setSnapshotCopyDestinationRegion("us-west-2");
        east.setSnapshotCopyGrantName("copies");
        clusters.putForAccount(ACCOUNT, "east", east);

        service("us-east-1").migrateRegionalRecords();

        assertEquals(Set.of(regionalKey("us-east-1", "analytics"), regionalKey("eu-west-1", "analytics")),
                groups.keysForAccount(ACCOUNT));
        assertEquals(Set.of(regionalKey("us-east-1", "private")), subnets.keysForAccount(ACCOUNT));
        assertEquals(Set.of(regionalKey("us-east-1", "nightly"), regionalKey("eu-west-1", "nightly")),
                schedules.keysForAccount(ACCOUNT));
        assertEquals(Set.of(regionalKey("us-east-1", "copies"), regionalKey("us-west-2", "copies")),
                grants.keysForAccount(ACCOUNT));
        assertEquals("analytics", service("eu-west-1").describeClusterParameterGroups("analytics")
                .get(0).getParameterGroupName());
        assertEquals("ClusterSubnetGroupNotFoundFault", assertThrows(AwsException.class,
                () -> service("eu-west-1").describeClusterSubnetGroups("private")).getErrorCode());
    }

    /**
     * Each Region a record is copied to gets one of its own: changing or tagging one Region's record leaves the
     * other Region's as it was.
     */
    @Test
    void eachRegionGetsARecordOfItsOwn() {
        groups.putForAccount(ACCOUNT, "analytics", new ClusterParameterGroup("analytics", "redshift-1.0", "d"));
        grants.putForAccount(ACCOUNT, "copies", new SnapshotCopyGrant("copies", "key"));
        Cluster west = pausedCluster("west", "eu-west-1");
        west.setClusterParameterGroupName("analytics");
        west.setSnapshotCopyDestinationRegion("us-west-2");
        west.setSnapshotCopyGrantName("copies");
        clusters.putForAccount(ACCOUNT, "west", west);
        service("us-east-1").migrateRegionalRecords();

        RedshiftService westService = service("eu-west-1");
        westService.modifyClusterParameterGroup("analytics",
                List.of(new Parameter(CURSOR_SIZE, "1000")));
        westService.createTags("arn:aws:redshift:eu-west-1:" + ACCOUNT + ":parametergroup:analytics",
                Map.of("team", "west"));
        service("us-west-2").createTags(
                "arn:aws:redshift:us-west-2:" + ACCOUNT + ":snapshotcopygrant:copies", Map.of("team", "west"));

        ClusterParameterGroup eastGroup = groups.getForAccount(ACCOUNT, regionalKey("us-east-1", "analytics"))
                .orElseThrow();
        ClusterParameterGroup westGroup = groups.getForAccount(ACCOUNT, regionalKey("eu-west-1", "analytics"))
                .orElseThrow();
        assertEquals("1000", parameterValue(westGroup, CURSOR_SIZE));
        assertEquals(parameterValue(new ClusterParameterGroup("analytics", "redshift-1.0", "d"), CURSOR_SIZE),
                parameterValue(eastGroup, CURSOR_SIZE));
        assertEquals(Map.of("team", "west"), westGroup.getTags());
        assertEquals(Map.of(), eastGroup.getTags());
        assertEquals(Map.of("team", "west"),
                grants.getForAccount(ACCOUNT, regionalKey("us-west-2", "copies")).orElseThrow().getTags());
        assertEquals(Map.of(),
                grants.getForAccount(ACCOUNT, regionalKey("us-east-1", "copies")).orElseThrow().getTags());
    }

    /**
     * A grant copied to another Region that held its first Region's default key, as a grant created without
     * {@code KmsKeyId} does, holds the other Region's default key; a key the grant named stays as it was.
     */
    @Test
    void aGrantCopiedToAnotherRegionTakesThatRegionsDefaultKey() {
        String eastDefault = "arn:aws:kms:us-east-1:" + ACCOUNT + ":alias/aws/redshift";
        String named = "arn:aws:kms:us-east-1:" + ACCOUNT + ":key/1234abcd-12ab-34cd-56ef-1234567890ab";
        grants.putForAccount(ACCOUNT, "defaulted", new SnapshotCopyGrant("defaulted", eastDefault));
        grants.putForAccount(ACCOUNT, "named", new SnapshotCopyGrant("named", named));
        Cluster first = pausedCluster("first", null);
        first.setSnapshotCopyDestinationRegion("us-west-2");
        first.setSnapshotCopyGrantName("defaulted");
        clusters.putForAccount(ACCOUNT, "first", first);
        Cluster second = pausedCluster("second", null);
        second.setSnapshotCopyDestinationRegion("us-west-2");
        second.setSnapshotCopyGrantName("named");
        clusters.putForAccount(ACCOUNT, "second", second);

        service("us-east-1").migrateRegionalRecords();

        assertEquals("arn:aws:kms:us-west-2:" + ACCOUNT + ":alias/aws/redshift",
                grants.getForAccount(ACCOUNT, regionalKey("us-west-2", "defaulted")).orElseThrow().getKmsKeyId());
        assertEquals(eastDefault,
                grants.getForAccount(ACCOUNT, regionalKey("us-east-1", "defaulted")).orElseThrow().getKmsKeyId());
        assertEquals(named,
                grants.getForAccount(ACCOUNT, regionalKey("us-west-2", "named")).orElseThrow().getKmsKeyId());
    }

    /**
     * A grant created in another Region holds that Region's key, so it moves there with its key, and not to the
     * default Region. A grant whose key is no ARN moves to the default Region.
     */
    @Test
    void aGrantMovesToTheRegionOfItsKey() {
        String westDefault = "arn:aws:kms:us-west-2:" + ACCOUNT + ":alias/aws/redshift";
        grants.putForAccount(ACCOUNT, "copies", new SnapshotCopyGrant("copies", westDefault));
        grants.putForAccount(ACCOUNT, "keyless", new SnapshotCopyGrant("keyless", "1234abcd"));
        Cluster east = pausedCluster("east", null);
        east.setSnapshotCopyDestinationRegion("us-west-2");
        east.setSnapshotCopyGrantName("copies");
        clusters.putForAccount(ACCOUNT, "east", east);

        service("us-east-1").migrateRegionalRecords();

        assertEquals(Set.of(regionalKey("us-west-2", "copies"), regionalKey("us-east-1", "keyless")),
                grants.keysForAccount(ACCOUNT));
        assertEquals(westDefault,
                grants.getForAccount(ACCOUNT, regionalKey("us-west-2", "copies")).orElseThrow().getKmsKeyId());
    }

    /**
     * A subnet group whose subnets EC2 placed in one Region moves to that Region, and not to the default Region,
     * unless a cluster there uses it: then the default Region gets a copy. One whose subnets span Regions, or that EC2
     * never placed, moves to the default Region.
     */
    @Test
    void aSubnetGroupMovesToTheRegionOfItsSubnets() {
        ClusterSubnetGroup west = new ClusterSubnetGroup("west", "d", "vpc-1", List.of("subnet-a", "subnet-b"));
        west.setSubnetZones(Map.of("subnet-a", "eu-west-1a", "subnet-b", "eu-west-1b"));
        subnets.putForAccount(ACCOUNT, "west", west);
        ClusterSubnetGroup spread = new ClusterSubnetGroup("spread", "d", "vpc-2", List.of("subnet-c", "subnet-d"));
        spread.setSubnetZones(Map.of("subnet-c", "eu-west-1a", "subnet-d", "us-west-2a"));
        subnets.putForAccount(ACCOUNT, "spread", spread);
        subnets.putForAccount(ACCOUNT, "unplaced", new ClusterSubnetGroup("unplaced", "d", null, List.of("subnet-e")));
        ClusterSubnetGroup shared = new ClusterSubnetGroup("shared", "d", "vpc-3", List.of("subnet-f"));
        shared.setSubnetZones(Map.of("subnet-f", "eu-west-1c"));
        subnets.putForAccount(ACCOUNT, "shared", shared);
        Cluster east = pausedCluster("east", null);
        east.setClusterSubnetGroupName("shared");
        clusters.putForAccount(ACCOUNT, "east", east);

        service("us-east-1").migrateRegionalRecords();

        assertEquals(Set.of(regionalKey("eu-west-1", "west"), regionalKey("us-east-1", "spread"),
                regionalKey("us-east-1", "unplaced"), regionalKey("eu-west-1", "shared"),
                regionalKey("us-east-1", "shared")), subnets.keysForAccount(ACCOUNT));
    }

    /**
     * A service given a plain ObjectMapper, without the Java time module, still copies a record carrying an
     * {@code Instant}: a snapshot schedule's {@code updatedAt}.
     */
    @Test
    void aRecordWithAnInstantIsCopiedWhateverMapperTheServiceWasGiven() {
        SnapshotSchedule nightly = new SnapshotSchedule("nightly", "d", List.of("rate(12 hours)"));
        nightly.setUpdatedAt(Instant.parse("2026-11-01T00:00:00Z"));
        schedules.putForAccount(ACCOUNT, "nightly", nightly);
        Cluster west = pausedCluster("west", "eu-west-1");
        west.setSnapshotScheduleIdentifier("nightly");
        clusters.putForAccount(ACCOUNT, "west", west);

        new RedshiftService(factory, mock(RedshiftContainerManager.class), config,
                new RegionResolver("us-east-1", ACCOUNT), mock(RedshiftProxyManager.class),
                mock(DockerHostResolver.class), new RedshiftCredentialBroker(), null, new ObjectMapper(), null)
                .migrateRegionalRecords();

        assertEquals(Instant.parse("2026-11-01T00:00:00Z"),
                schedules.getForAccount(ACCOUNT, regionalKey("eu-west-1", "nightly")).orElseThrow().getUpdatedAt());
    }

    private static final String CURSOR_SIZE = "max_cursor_result_set_size";

    private static String parameterValue(ClusterParameterGroup group, String name) {
        return group.getParameters().stream().filter(parameter -> name.equals(parameter.getParameterName()))
                .findFirst().orElseThrow().getParameterValue();
    }

    private static Cluster pausedCluster(String identifier, String region) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setClusterStatus("paused");
        cluster.setSnapshotRegion(region);
        return cluster;
    }

    /** A service whose calls run in {@code region}, its default Region too, over the test's stores. */
    private RedshiftService service(String region) {
        return new RedshiftService(factory, mock(RedshiftContainerManager.class), config,
                new RegionResolver(region, ACCOUNT), mock(RedshiftProxyManager.class), mock(DockerHostResolver.class),
                new RedshiftCredentialBroker());
    }
}
