package io.floci.aws.services.emr;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.LinkSigner;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrPersistentAppUi;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.util.List;

import static io.floci.aws.services.emr.EmrServiceFixture.ACCOUNT;
import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The account's cap on persistent application UIs, which ended clusters' UIs leave, via {@link EmrAppUiService}. */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EmrAppUiServiceTest {

    private final EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 0);
    private final EmrAppUiService appUis = new EmrAppUiService(
            AccountAwareStorageBackend.<EmrPersistentAppUi>inMemory(ACCOUNT), fixture.service,
            new RegionResolver(REGION, ACCOUNT), fixture.clock, "http://localhost:4566", LinkSigner.withNewKey());

    @Test
    void aUiOfAClusterThatEndedMoreThanThirtyDaysAgoNoLongerCountsAgainstTheCap() {
        String ended = sparkCluster();
        for (int i = 0; i < EmrAppUiService.MAX_PERSISTENT_APP_UIS; i++) {
            appUis.create(arn(ended), null, null);
        }
        String next = sparkCluster();
        AwsException full = assertThrows(AwsException.class, () -> appUis.create(arn(next), null, null));
        assertTrue(full.getMessage().contains("200 persistent application UIs"), full.getMessage());

        fixture.service.terminateJobFlows(List.of(ended));
        fixture.clock.advance(EmrAppUiService.LOGS_KEPT.minus(Duration.ofMinutes(1)));
        assertThrows(AwsException.class, () -> appUis.create(arn(next), null, null));
        fixture.clock.advance(Duration.ofMinutes(2));

        assertEquals("ACTIVE", appUis.create(arn(next), null, null).appUi().getStatus());
    }

    private String sparkCluster() {
        EmrCluster cluster = cluster("app-uis", true);
        cluster.setReleaseLabel("emr-6.15.0");
        cluster.setApplications("[{\"Name\":\"Spark\"}]");
        return fixture.service.runJobFlow(cluster, REGION).getId();
    }

    private static String arn(String clusterId) {
        return "arn:aws:elasticmapreduce:us-east-1:000000000000:cluster/" + clusterId;
    }
}
