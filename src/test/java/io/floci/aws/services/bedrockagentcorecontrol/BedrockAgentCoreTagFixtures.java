package io.floci.aws.services.bedrockagentcorecontrol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BedrockAgentCoreTagFixtures {

    private BedrockAgentCoreTagFixtures() {
    }

    static Map<String, String> numberedTags(int count) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            tags.put("key" + i, "value");
        }
        return tags;
    }

    static List<Map<String, String>> invalidTagMaps() {
        return List.of(numberedTags(51), Map.of("", "value"),
                Map.of("k".repeat(129), "value"), Map.of("bad#key", "value"),
                Map.of("key", "v".repeat(257)), Map.of("key", "bad#value"));
    }

    static List<String> malformedTagBodies() {
        return List.of("{}", "{\"tags\":null}", "{\"tags\":[]}",
                "{\"tags\":{\"key\":1}}", "{\"tags\":{\"key\":null}}", "[]");
    }
}
