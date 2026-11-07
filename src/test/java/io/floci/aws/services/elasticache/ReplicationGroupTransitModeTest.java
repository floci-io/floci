package io.floci.aws.services.elasticache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplicationGroupTransitModeTest {

    @Test
    void persistedModeRoundTripsAndLegacyRecordsLeaveItUnset() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReplicationGroup group = new ReplicationGroup();
        group.setTransitEncryptionMode("required");
        ReplicationGroup restored = mapper.readValue(mapper.writeValueAsBytes(group), ReplicationGroup.class);
        assertEquals("required", restored.getTransitEncryptionMode());
        assertNull(mapper.readValue("{}", ReplicationGroup.class).getTransitEncryptionMode());
    }
}
