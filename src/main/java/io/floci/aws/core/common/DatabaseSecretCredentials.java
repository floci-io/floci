package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Username and password credentials stored in an AWS Secrets Manager secret for a database (RDS,
 * Redshift, Redshift Data API, or Firehose Redshift destination).
 */
public record DatabaseSecretCredentials(String username, String password) {

    private static final Logger LOG = Logger.getLogger(DatabaseSecretCredentials.class);
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    public DatabaseSecretCredentials {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(password, "password must not be null");
    }

    /**
     * Parses credentials from a Secrets Manager secret string JSON payload using the default mapper.
     */
    public static Optional<DatabaseSecretCredentials> parse(String secretString) {
        return parse(DEFAULT_MAPPER, secretString);
    }

    /**
     * Parses credentials from a Secrets Manager secret string using the given ObjectMapper.
     */
    public static Optional<DatabaseSecretCredentials> parse(ObjectMapper mapper, String secretString) {
        if (secretString == null || secretString.isBlank() || mapper == null) {
            return Optional.empty();
        }
        try {
            return parse(mapper.readTree(secretString));
        } catch (Exception ignored) {
            // An unparseable secret string is treated as missing credentials; callers handle Optional.empty()
            LOG.debugv("Failed to parse database credentials from secret string: {0}", ignored.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses credentials from a Secrets Manager secret JSON node.
     * Looks for non-null "username" (or fallback "user") and "password" text fields.
     */
    public static Optional<DatabaseSecretCredentials> parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        String username = textOrNull(node, "username");
        if (username == null) {
            username = textOrNull(node, "user");
        }
        String password = textOrNull(node, "password");
        if (username != null && password != null) {
            return Optional.of(new DatabaseSecretCredentials(username, password));
        }
        return Optional.empty();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}
