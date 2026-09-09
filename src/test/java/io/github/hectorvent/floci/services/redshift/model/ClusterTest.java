package io.github.hectorvent.floci.services.redshift.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClusterTest {

    @Test
    void proxyAndContainerFieldsDefaultToEmpty() {
        Cluster cluster = new Cluster();
        assertNull(cluster.getContainerHost());
        assertEquals(0, cluster.getContainerPort());
        assertEquals(0, cluster.getProxyPort());
    }

    @Test
    void proxyAndContainerFieldsRoundTripThroughJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setContainerHost("172.17.0.4");
        cluster.setContainerPort(32771);
        cluster.setProxyPort(7100);

        Cluster restored = mapper.readValue(mapper.writeValueAsString(cluster), Cluster.class);

        assertEquals("172.17.0.4", restored.getContainerHost());
        assertEquals(32771, restored.getContainerPort());
        assertEquals(7100, restored.getProxyPort());
    }

    @Test
    void jsonWithoutNewFieldsDeserializesWithDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Cluster restored = mapper.readValue(
                "{\"clusterIdentifier\":\"legacy\",\"clusterStatus\":\"available\"}", Cluster.class);

        assertNull(restored.getContainerHost());
        assertEquals(0, restored.getContainerPort());
        assertEquals(0, restored.getProxyPort());
    }
}
