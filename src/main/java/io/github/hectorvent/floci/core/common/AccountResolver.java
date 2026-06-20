package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AccountResolver {

    private static final Pattern AKID_PATTERN = Pattern.compile("Credential=([^/]+)/");

    private final String defaultAccountId;

    @Inject
    public AccountResolver(EmulatorConfig config) {
        this.defaultAccountId = config.defaultAccountId();
    }

    public AccountResolver(String defaultAccountId) {
        this.defaultAccountId = defaultAccountId;
    }

    /**
     * Returns the account ID for the given Authorization header.
     * When the access key ID is exactly 12 digits it is used directly as the account ID,
     * matching LocalStack's multi-account convention. Any other key format falls back to
     * the configured default account.
     */
    public String resolve(String authorizationHeader) {
        String akid = extractAccessKeyId(authorizationHeader);
        if (akid != null && akid.matches("\\d{12}")) {
            return akid;
        }
        return defaultAccountId;
    }

    /**
     * Extracts the raw access key ID from an AWS SigV4 Authorization header,
     * or returns null if the header is absent or does not contain a Credential field.
     */
    public String extractAccessKeyId(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        Matcher m = AKID_PATTERN.matcher(authorizationHeader);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Resolves the account ID from an X-Amz-Credential value found in
     * presigned URL query parameters.
     * Format: accessKeyID/date/region/service/aws4_request
     * When the access key ID is exactly 12 digits it is used directly
     * as the account ID, matching LocalStack's multi-account convention.
     * Any other key format or missing/malformed value falls back to
     * the configured default account.
     */
    public String resolveFromPresignedCredential(String credentialValue) {
        if (credentialValue == null || credentialValue.isEmpty()) {
            return defaultAccountId;
        }
        String[] parts = credentialValue.split("/");
        String akid = parts[0];
        if (akid.matches("\\d{12}")) {
            return akid;
        }
        return defaultAccountId;
    }
}
