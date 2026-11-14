package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
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
import io.floci.aws.services.redshift.model.SnapshotCopyGrant;
import io.floci.aws.services.redshift.model.SnapshotSchedule;
import io.floci.aws.services.redshift.proxy.RedshiftProxyManager;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Cluster parameter groups, subnet groups, snapshot schedules and snapshot copy grants were stored under their name
 * alone and seen from every Region. At startup each moves to the default Region, and is copied to every other
 * Region where one of its account's clusters uses it: a cluster's parameter group, subnet group and schedule in the
 * cluster's Region, its copy grant in the copy's destination Region.
 */
class RedshiftRegionalRecordsMigrationTest {

    private static final String ACCOUNT = "111111111111";

    @TempDir
    Path directory;

    @Test
    void recordsStoredWithoutARegionMoveToTheDefaultRegionAndToEachRegionWhoseClustersUseThem() {
        EmulatorConfig config = StorageFactories.config(directory, ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, "persistent");
        try {
            AccountAwareStorageBackend<ClusterParameterGroup> groups = factory.create("redshift", "redshift-parameter-groups.json",
                    new TypeReference<Map<String, ClusterParameterGroup>>() { });
            AccountAwareStorageBackend<ClusterSubnetGroup> subnets = factory.create("redshift", "redshift-subnet-groups.json",
                    new TypeReference<Map<String, ClusterSubnetGroup>>() { });
            AccountAwareStorageBackend<SnapshotSchedule> schedules = factory.create("redshift", "redshift-snapshot-schedules.json",
                    new TypeReference<Map<String, SnapshotSchedule>>() { });
            AccountAwareStorageBackend<SnapshotCopyGrant> grants = factory.create("redshift", "redshift-snapshot-copy-grants.json",
                    new TypeReference<Map<String, SnapshotCopyGrant>>() { });
            AccountAwareStorageBackend<Cluster> clusters = factory.create("redshift", "redshift-clusters.json",
                    new TypeReference<Map<String, Cluster>>() { });
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

            service(factory, config, "us-east-1").onStart(null);

            assertEquals(Set.of("us-east-1::analytics", "eu-west-1::analytics"), groups.keysForAccount(ACCOUNT));
            assertEquals(Set.of("us-east-1::private"), subnets.keysForAccount(ACCOUNT));
            assertEquals(Set.of("us-east-1::nightly", "eu-west-1::nightly"), schedules.keysForAccount(ACCOUNT));
            assertEquals(Set.of("us-east-1::copies", "us-west-2::copies"), grants.keysForAccount(ACCOUNT));
            assertEquals("analytics", service(factory, config, "eu-west-1").describeClusterParameterGroups("analytics")
                    .get(0).getParameterGroupName());
            assertEquals("ClusterSubnetGroupNotFoundFault", assertThrows(AwsException.class,
                    () -> service(factory, config, "eu-west-1").describeClusterSubnetGroups("private")).getErrorCode());
        } finally {
            factory.shutdownAll();
        }
    }

    private static Cluster pausedCluster(String identifier, String region) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setClusterStatus("paused");
        cluster.setSnapshotRegion(region);
        return cluster;
    }

    /** A service whose calls run in {@code region}, its default Region too, over {@code factory}'s stores. */
    private static RedshiftService service(StorageFactory factory, EmulatorConfig config, String region) {
        return new RedshiftService(factory, mock(RedshiftContainerManager.class), config,
                new RegionResolver(region, ACCOUNT), mock(RedshiftProxyManager.class), mock(DockerHostResolver.class),
                new RedshiftCredentialBroker());
    }
}
