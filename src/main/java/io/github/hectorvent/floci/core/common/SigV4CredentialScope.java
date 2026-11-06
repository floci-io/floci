package io.github.hectorvent.floci.core.common;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the parts of an Authorization header's SigV4 credential scope
 * ({@code Credential=<key>/<date>/<region>/<service>/aws4_request}): the signing service name,
 * and the access key id the request signed with.
 */
public final class SigV4CredentialScope {

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=[^,\\s]+/\\d{8}/[^/,\\s]+/([^/,\\s]+)/aws4_request");
    // SigV4A drops the region segment: Credential=<key>/<date>/<service>/aws4_request
    private static final Pattern SIGV4A_SERVICE_PATTERN =
            Pattern.compile("Credential=[^,\\s]+/\\d{8}/([^/,\\s]+)/aws4_request");

    static final String CREDENTIAL = "Credential=";
    private static final String TERMINATOR = "aws4_request";

    private SigV4CredentialScope() {
    }

    /** A credential scope segment that {@link #segment} reads. */
    enum Segment {
        /** The region, or the service in the SigV4A form, which has no region. */
        THIRD,
        SERVICE
    }

    /**
     * Reads one segment of the common header shape without a regular expression. Returns null for
     * any other shape, and the caller then runs its regex, so both give the same answer for every input.
     */
    static String segment(String header, Segment segment) {
        int credential = header.indexOf(CREDENTIAL);
        if (credential < 0 || header.indexOf(CREDENTIAL, credential + 1) >= 0) {
            return null;
        }
        int start = credential + CREDENTIAL.length();
        int end = start;
        int length = header.length();
        while (end < length) {
            char c = header.charAt(end);
            if (c == ',' || isRegexWhitespace(c)) {
                break;
            }
            end++;
        }
        if (header.indexOf('/', end) >= 0) {
            return null;
        }
        int slash1 = header.indexOf('/', start);
        int slash2 = slash1 < 0 ? -1 : header.indexOf('/', slash1 + 1);
        int slash3 = slash2 < 0 ? -1 : header.indexOf('/', slash2 + 1);
        if (slash3 < 0) {
            return null;
        }
        int slash4 = header.indexOf('/', slash3 + 1);
        int terminator = slash4 < 0 ? slash3 : slash4;
        if (end - terminator - 1 != TERMINATOR.length() || !header.startsWith(TERMINATOR, terminator + 1)) {
            return null;
        }
        if (slash1 == start || slash2 == slash1 + 1 || slash3 == slash2 + 1
                || !isEightDigits(header, slash1 + 1, slash2)) {
            return null;
        }
        if (slash4 >= 0 && (slash4 == slash3 + 1 || isEightDigits(header, slash2 + 1, slash3))) {
            return null;
        }
        return switch (segment) {
            case THIRD -> header.substring(slash2 + 1, slash3);
            case SERVICE -> slash4 < 0 ? header.substring(slash2 + 1, slash3) : header.substring(slash3 + 1, slash4);
        };
    }

    /** Every credential scope pattern starts with {@link #CREDENTIAL}, so a header without it matches none. */
    static boolean hasCredential(String header) {
        return header.contains(CREDENTIAL);
    }

    private static boolean isRegexWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean isEightDigits(String value, int from, int to) {
        if (to - from != 8) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if (c < '0' || '9' < c) {
                return false;
            }
        }
        return true;
    }

    public static Optional<String> serviceName(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        String service = segment(authorization, Segment.SERVICE);
        if (service != null) {
            return Optional.of(service.toLowerCase(Locale.ROOT));
        }
        if (!hasCredential(authorization)) {
            return Optional.empty();
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher sigV4a = SIGV4A_SERVICE_PATTERN.matcher(authorization);
        return sigV4a.find() ? Optional.of(sigV4a.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /**
     * The access key id the request signed with, which is the credential scope's first segment.
     * Not lower-cased, unlike the service name: an access key id is case sensitive, and in Floci
     * that segment may also carry an account id used for routing.
     */
    public static Optional<String> accessKeyId(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        for (int credential = authorization.indexOf(CREDENTIAL); credential >= 0;
                credential = authorization.indexOf(CREDENTIAL, credential + 1)) {
            int start = credential + CREDENTIAL.length();
            int end = start;
            while (end < authorization.length()) {
                char c = authorization.charAt(end);
                if (c == '/' || c == ',' || isRegexWhitespace(c)) {
                    break;
                }
                end++;
            }
            if (start < end && end < authorization.length() && authorization.charAt(end) == '/') {
                return Optional.of(authorization.substring(start, end));
            }
        }
        return Optional.empty();
    }

    public static Optional<String> serviceNameFromCredential(String credential) {
        if (credential == null) {
            return Optional.empty();
        }
        return serviceName("Credential=" + credential);
    }
}
