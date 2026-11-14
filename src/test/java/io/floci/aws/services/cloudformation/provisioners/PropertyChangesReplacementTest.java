package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyChangesReplacementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "AWS::Route53::HostedZone";

    private static PropertyChanges between(String prior, String current) throws Exception {
        return PropertyChanges.between(TYPE, true, MAPPER.readTree(prior), MAPPER.readTree(current));
    }

    @Test
    void aChangedOrDroppedCreateOnlyPropertyReplaces() throws Exception {
        assertTrue(between("{\"Name\":\"a.example.\"}", "{\"Name\":\"b.example.\"}").requiresReplacement());
        assertTrue(between("{\"Name\":\"a.example.\",\"DelegationSetId\":\"N1\"}",
                "{\"Name\":\"a.example.\"}").requiresReplacement());
    }

    @Test
    void anAddedOrNonCreateOnlyChangeDoesNotReplace() throws Exception {
        assertFalse(between("{\"Name\":\"a.example.\"}",
                "{\"Name\":\"a.example.\",\"DelegationSetId\":\"N1\"}").requiresReplacement());
        assertFalse(between("{\"Name\":\"a.example.\",\"HostedZoneConfig\":{\"Comment\":\"x\"}}",
                "{\"Name\":\"a.example.\",\"HostedZoneConfig\":{\"Comment\":\"y\"}}").requiresReplacement());
    }
}
