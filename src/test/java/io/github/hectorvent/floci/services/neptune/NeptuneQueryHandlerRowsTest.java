package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NeptuneQueryHandlerRowsTest {

    private static NeptuneCluster cluster(String id, String region) {
        NeptuneCluster c = new NeptuneCluster();
        c.setDbClusterIdentifier(id);
        c.setStatus("available");
        c.setDbClusterArn("arn:aws:rds:" + region + ":000000000000:cluster:" + id);
        return c;
    }

    @Test
    void rowsForTheFamilyListingAreScopedToTheRequestRegionByArn() {
        // the Neptune store is not keyed by region: the request's region has to be read off the ARN
        NeptuneService service = mock(NeptuneService.class);
        when(service.listDbClusters(isNull())).thenReturn(List.of(
                cluster("east", "us-east-1"), cluster("west", "eu-west-1")));
        NeptuneQueryHandler handler = new NeptuneQueryHandler(service, mock(EmulatorConfig.class));

        List<String> rows = handler.clusterRowsXml(null, "us-east-1");

        assertEquals(1, rows.size(), rows.toString());
        assertTrue(rows.getFirst().contains("<DBClusterIdentifier>east</DBClusterIdentifier>"), rows.getFirst());
        assertTrue(rows.getFirst().contains("<Engine>neptune</Engine>"), rows.getFirst());
    }
}
