package io.floci.aws.services.elbv2;

import java.util.List;

final class PathRegexFixtures {

    static final List<String> MATCH_PATTERNS = List.of(
            "^/api/(items|orders)(/.*)?$", "^/status/[0-9]+$");

    private PathRegexFixtures() {
    }
}
