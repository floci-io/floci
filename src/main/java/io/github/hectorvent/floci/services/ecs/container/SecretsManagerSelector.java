package io.github.hectorvent.floci.services.ecs.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

/**
 * The ECS task-definition secret selector for Secrets Manager {@code valueFrom} values:
 * {@code arn:aws:secretsmanager:region:account:secret:name:json-key:version-stage:version-id}.
 *
 * <p>Parsing mirrors the real ECS agent (amazon-ecs-agent, asmsecret.go): the ARN's resource
 * segment is split on {@code :} and disambiguated purely by segment count, which is safe
 * because a secret name can never contain a colon. Exactly two segments
 * ({@code secret:name}) is a plain reference and the original string is used verbatim as the
 * secret id, preserving partial-ARN lookups. Exactly five segments is a selector: the secret
 * id is rebuilt from the first two segments, and empty selector segments mean "unset" — CDK
 * routinely emits forms like {@code :field::}, {@code :::version-id} and
 * {@code ::version-stage:}. Any other count is the agent's invalid-ARN error.
 */
record SecretsManagerSelector(String secretId, String jsonKey, String versionStage, String versionId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INVALID_ARN_MESSAGE =
            "an invalid ARN format for the AWS Secrets Manager secret was specified. "
                    + "Specify a valid ARN and try again.";

    static SecretsManagerSelector parse(String valueFrom) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(valueFrom);
        } catch (IllegalArgumentException e) {
            throw invalidArn();
        }
        String[] segments = arn.resource().split(":", -1);
        if (segments.length == 2) {
            return new SecretsManagerSelector(valueFrom, null, null, null);
        }
        if (segments.length != 5) {
            throw invalidArn();
        }
        String baseArn = new AwsArnUtils.Arn(arn.partition(), arn.service(), arn.region(),
                arn.accountId(), segments[0] + ":" + segments[1]).toString();
        return new SecretsManagerSelector(baseArn,
                emptyToNull(segments[2]), emptyToNull(segments[3]), emptyToNull(segments[4]));
    }

    /**
     * Extracts a top-level JSON field from a secret string, mirroring the agent's behavior
     * (asm.go): missing key and non-JSON secrets are errors. Scalar values are rendered with
     * {@code asText()}, which keeps large numbers in plain notation where the Go agent's
     * {@code %v} would print scientific notation. Object and array values deliberately serialize
     * as JSON, another divergence: the agent's {@code %v} on the unmarshalled value would inject
     * Go's native rendering ({@code map[a:1 b:2]}), which no consumer can parse back.
     */
    static String extractJsonKey(String secretString, String jsonKey) {
        JsonNode root;
        try {
            root = MAPPER.readTree(secretString);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new AwsException("InvalidParameterException",
                    "the secret value is not valid JSON, cannot extract json key " + jsonKey, 400);
        }
        JsonNode value = root.get(jsonKey);
        if (value == null) {
            throw new AwsException("ResourceNotFoundException",
                    "retrieved secret from Secrets Manager did not contain json key " + jsonKey, 400);
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String emptyToNull(String segment) {
        return segment == null || segment.isEmpty() ? null : segment;
    }

    private static AwsException invalidArn() {
        return new AwsException("InvalidParameterException", INVALID_ARN_MESSAGE, 400);
    }
}
