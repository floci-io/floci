package io.github.hectorvent.floci.core.common;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the SigV4 signing service name from an Authorization header's
 * credential scope ({@code Credential=<key>/<date>/<region>/<service>/aws4_request}).
 */
final class SigV4CredentialScope {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    private SigV4CredentialScope() {
    }

    static Optional<String> serviceName(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }
}
