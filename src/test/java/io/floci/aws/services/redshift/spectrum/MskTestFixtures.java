package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.kafka.KarapaceManager;
import io.floci.aws.services.msk.model.ClusterState;
import io.floci.aws.services.msk.model.MskCluster;

import java.net.URI;

import static org.mockito.Mockito.when;

/** The MSK cluster and Kafka REST bridge the streaming view tests read from. */
final class MskTestFixtures {

    private MskTestFixtures() {
    }

    /** A cluster that is ACTIVE and has a broker to bootstrap from. */
    static MskCluster activeCluster(String clusterArn, String bootstrapBrokers) {
        MskCluster cluster = new MskCluster(clusterArn, "orders", "3.6.0");
        cluster.setState(ClusterState.ACTIVE);
        cluster.setBootstrapBrokers(bootstrapBrokers);
        return cluster;
    }

    /** Makes the bridge for {@code bootstrapBrokers} answer at {@code restBaseUri}. */
    static void bridgeAt(KarapaceManager karapace, String bootstrapBrokers, URI restBaseUri) {
        when(karapace.ensureStarted(bootstrapBrokers)).thenReturn(restBaseUri);
    }
}
