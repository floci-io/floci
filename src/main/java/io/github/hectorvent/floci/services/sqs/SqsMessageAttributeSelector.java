package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class SqsMessageAttributeSelector {

    private SqsMessageAttributeSelector() {
    }

    static Map<String, MessageAttributeValue> select(Map<String, MessageAttributeValue> attributes,
                                                     Set<String> requestedNames) {
        if (attributes == null || attributes.isEmpty() || requestedNames.isEmpty()) {
            return Map.of();
        }
        Map<String, MessageAttributeValue> selected = new LinkedHashMap<>();
        for (Map.Entry<String, MessageAttributeValue> entry : attributes.entrySet()) {
            if (includes(requestedNames, entry.getKey())) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }

    private static boolean includes(Set<String> requestedNames, String name) {
        if (requestedNames.contains("All") || requestedNames.contains(".*") || requestedNames.contains(name)) {
            return true;
        }
        for (String requestedName : requestedNames) {
            if (requestedName.endsWith(".*") && name.startsWith(requestedName.substring(0, requestedName.length() - 2))) {
                return true;
            }
        }
        return false;
    }
}
