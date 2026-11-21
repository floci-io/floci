package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsException;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/** Request validation shared by the XKS Custom Key Store management operations. */
final class XksCustomKeyStoreValidation {

    static final Pattern ACCESS_KEY_ID = Pattern.compile("[A-Z2-7]{20,30}");
    static final Pattern SECRET_ACCESS_KEY = Pattern.compile("[A-Za-z0-9+/=]{43,64}");
    private static final Pattern CUSTOM_KEY_STORE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_NAME_LENGTH = 256;
    static final int MAX_PAGE_SIZE = 1000;

    private XksCustomKeyStoreValidation() {
    }

    static String requiredName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw validation("CustomKeyStoreName must contain between 1 and 256 characters");
        }
        return name;
    }

    static ProxyConfiguration proxyConfiguration(String endpoint, String path,
                                                 String accessKeyId, String secretAccessKey) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() < 10 || endpoint.length() > 128) {
            throw validation("XksProxyUriEndpoint is required");
        }
        if (path == null || path.length() < 10 || path.length() > 128) {
            throw validation("XksProxyUriPath is required");
        }
        if (accessKeyId == null || !ACCESS_KEY_ID.matcher(accessKeyId).matches()) {
            throw validation("XksProxyAuthenticationCredential.AccessKeyId is invalid");
        }
        if (secretAccessKey == null || !SECRET_ACCESS_KEY.matcher(secretAccessKey).matches()) {
            throw validation("XksProxyAuthenticationCredential.RawSecretAccessKey is invalid");
        }
        final URI parsed;
        try {
            parsed = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw validation("XksProxyUriEndpoint is invalid");
        }
        // Reuse the XKS client's strict endpoint/path rules so validation and the eventual
        // health-check request cannot disagree about the URI that will be contacted.
        XksProxyClient.healthUri(parsed, path);
        return new ProxyConfiguration(parsed, path, accessKeyId, secretAccessKey);
    }

    static void validateDescribeFilters(List<String> ids, List<String> names) {
        boolean hasIds = ids != null && !ids.isEmpty();
        boolean hasNames = names != null && !names.isEmpty();
        if (hasIds && hasNames) {
            throw validation("CustomKeyStoreId and CustomKeyStoreName filters cannot both be specified");
        }
        if (hasIds && ids.stream().anyMatch(value -> value == null || !CUSTOM_KEY_STORE_ID.matcher(value).matches())) {
            throw validation("CustomKeyStoreId must contain between 1 and 64 valid characters");
        }
        if (hasNames && names.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > MAX_NAME_LENGTH)) {
            throw validation("CustomKeyStoreName must contain between 1 and 256 characters");
        }
    }

    static int pageSize(Integer limit) {
        if (limit == null) {
            return MAX_PAGE_SIZE;
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw validation("Limit must be between 1 and 1000");
        }
        return limit;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    record ProxyConfiguration(URI endpoint, String path, String accessKeyId, String secretAccessKey) {
        @Override
        public String toString() {
            return "ProxyConfiguration[endpoint=" + endpoint + ", path=" + path
                    + ", accessKeyId=" + accessKeyId + ", secretAccessKey=<redacted>]";
        }
    }
}
