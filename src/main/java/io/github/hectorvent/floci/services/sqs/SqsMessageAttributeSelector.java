package io.github.hectorvent.floci.services.sqs;

import java.util.Set;

final class SqsMessageAttributeSelector {

    private SqsMessageAttributeSelector() {
    }

    static boolean includes(Set<String> requestedNames, String name) {
        if (requestedNames.contains("All") || requestedNames.contains(".*") || requestedNames.contains(name)) {
            return true;
        }
        for (String requestedName : requestedNames) {
            if (requestedName.endsWith(".*") && name.startsWith(requestedName.substring(0, requestedName.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
