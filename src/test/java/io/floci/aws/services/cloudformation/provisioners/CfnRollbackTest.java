package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CfnRollbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A stored tag snapshot reads back the keys sorted and the values as the resource held them:
     * a key it did not carry has no value, and a null value stays null rather than the text "null".
     */
    @Test
    void aStoredTagSnapshotReadsBackTheTagsAsTheyWere() throws Exception {
        Map<String, String> current = new HashMap<>();
        current.put("kept", "value");
        current.put("unset", null);
        ObjectNode snapshot = MAPPER.createObjectNode();

        CfnRollback.putTagSnapshot(snapshot, List.of("unset", "kept", "absent"), current);

        ObjectNode stored = (ObjectNode) MAPPER.readTree(snapshot.toString());
        assertEquals(List.of("absent", "kept", "unset"), CfnRollback.snapshotTagKeys(stored));
        assertEquals(current, CfnRollback.snapshotTags(stored));
    }
}
