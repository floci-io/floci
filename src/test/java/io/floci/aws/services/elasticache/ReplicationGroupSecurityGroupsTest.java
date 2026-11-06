package io.floci.aws.services.elasticache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplicationGroupSecurityGroupsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void securityGroupsSurviveThePersistedJsonRoundTrip() throws Exception {
        ReplicationGroup group = mapper.readValue("""
                {"replicationGroupId":"restored","securityGroupIds":["sg-first","sg-second"]}
                """, ReplicationGroup.class);

        ReplicationGroup restored = mapper.readValue(mapper.writeValueAsString(group), ReplicationGroup.class);

        assertEquals(mapper.readTree("[\"sg-first\",\"sg-second\"]"),
                mapper.valueToTree(restored).get("securityGroupIds"));
    }

    @Test
    void legacyRecordsWithoutSecurityGroupsKeepAnEmptyList() throws Exception {
        ReplicationGroup group = mapper.readValue("{\"replicationGroupId\":\"legacy\"}", ReplicationGroup.class);

        assertEquals(mapper.readTree("[]"), mapper.valueToTree(group).get("securityGroupIds"));
    }

    @Test
    void explicitNullSecurityGroupsNormalizeToAnEmptyList() throws Exception {
        ReplicationGroup group = mapper.readValue("""
                {"replicationGroupId":"legacy-null","securityGroupIds":null}
                """, ReplicationGroup.class);

        assertEquals(mapper.readTree("[]"), mapper.valueToTree(group).get("securityGroupIds"));
    }
}
