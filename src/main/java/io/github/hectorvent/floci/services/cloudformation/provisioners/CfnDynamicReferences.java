package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves CloudFormation dynamic references ({@code {{resolve:secretsmanager:...}}},
 * {@code {{resolve:ssm:...}}}, {@code {{resolve:ssm-secure:...}}}) against the live services.
 *
 * <p>This is CloudFormation-wide behaviour that happens to be reached only from RDS today, and it
 * needs both Secrets Manager and SSM. Keeping it here rather than inside a per-service provisioner
 * lets {@code RdsCfnProvisioner} inject one collaborator instead of two unrelated services, and
 * lets any later provisioner reuse it without copying ~190 lines.
 */
@ApplicationScoped
public class CfnDynamicReferences {

    private final SecretsManagerService secretsManagerService;
    private final SsmService ssmService;
    private final ObjectMapper objectMapper;

    @Inject
    public CfnDynamicReferences(SecretsManagerService secretsManagerService, SsmService ssmService,
                                ObjectMapper objectMapper) {
        this.secretsManagerService = secretsManagerService;
        this.ssmService = ssmService;
        this.objectMapper = objectMapper;
    }

    private static final Pattern DYNAMIC_REF = Pattern.compile("\\{\\{resolve:([a-z-]+):(.*?)\\}\\}");
    private static final Pattern SSM_DYNAMIC_REF_BODY =
            Pattern.compile("([a-zA-Z0-9_.\\-/]+)(?::([0-9]+))?");

    /**
     * Resolves CloudFormation dynamic references embedded in a string. Supports
     * {@code {{resolve:secretsmanager:<secret-id-or-arn>:SecretString:<json-key>:<stage>:<version>}}}
     * and {@code {{resolve:ssm:<name>:<version>}}} / {@code {{resolve:ssm-secure:<name>:<version>}}},
     * which CloudFormation substitutes with the live value at deploy time (e.g. an RDS
     * MasterUserPassword sourced from a generated secret). Unsupported services are left verbatim.
     */
    public String resolveDynamicReferences(String value, String region, boolean allowSsmSecure) {
        if (value == null || !value.contains("{{resolve:")) {
            return value;
        }
        Matcher m = DYNAMIC_REF.matcher(value);
        StringBuilder sb = new StringBuilder();
        int previousEnd = 0;
        while (m.find()) {
            rejectUnclosedDynamicReference(value.substring(previousEnd, m.start()));
            String replacement = resolveDynamicRef(m.group(1), m.group(2), region, allowSsmSecure);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            previousEnd = m.end();
        }
        rejectUnclosedDynamicReference(value.substring(previousEnd));
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolveDynamicRef(String service, String body, String region, boolean allowSsmSecure) {
        if ("secretsmanager".equals(service)) {
            // body = <secret-id-or-arn>:SecretString:<json-key>:<version-stage>:<version-id>. The
            // secret id may be an ARN (which itself contains colons), so split on the ":SecretString"
            // marker rather than on ":". AWS also accepts <secret-id-or-arn>:::: as shorthand for
            // retrieving the whole current SecretString with the optional fields omitted.
            String secretId;
            String[] parts;
            if (body.endsWith("::::")) {
                secretId = body.substring(0, body.length() - 4);
                parts = new String[0];
            } else if (isValidSecretsManagerSecretId(body)) {
                secretId = body;
                parts = new String[0];
            } else {
                int marker = body.lastIndexOf(":SecretString");
                if (marker < 0) {
                    throw invalidSecretsManagerDynamicReference();
                }
                secretId = body.substring(0, marker);
                String rest = body.substring(marker + ":SecretString".length());
                if (!rest.isEmpty() && !rest.startsWith(":")) {
                    throw invalidSecretsManagerDynamicReference();
                }
                parts = rest.startsWith(":")
                        ? rest.substring(1).split(":", -1)
                        : new String[0];
            }
            if (!isValidSecretsManagerSecretId(secretId) || parts.length > 3) {
                throw invalidSecretsManagerDynamicReference();
            }
            String jsonKey = parts.length > 0 ? parts[0] : "";
            String versionStage = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
            String versionId = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
            if (versionStage != null && versionId != null) {
                throw new AwsException("ValidationError",
                        "version-stage and version-id cannot both be specified", 400);
            }
            String secretRegion = AwsArnUtils.regionOrDefault(secretId, region);
            String secretString = secretsManagerService
                    .getSecretValue(secretId, versionId, versionStage, secretRegion).getSecretString();
            if (secretString == null) {
                // A binary-only secret has no SecretString to substitute, so resource creation fails.
                throw new IllegalStateException(
                        "secret " + secretId + " has no SecretString value to resolve");
            }
            if (jsonKey.isBlank()) {
                return secretString;
            }
            JsonNode json;
            try {
                json = objectMapper.readTree(secretString);
            } catch (Exception e) {
                throw new AwsException("ValidationError",
                        "secret " + secretId + " does not contain valid JSON", 400);
            }
            if (!json.has(jsonKey)) {
                // A missing key would otherwise resolve to "" — silently provisioning e.g. a blank
                // MasterUserPassword. Fail resource creation instead.
                throw new IllegalStateException(
                        "JSON key '" + jsonKey + "' not found in secret " + secretId);
            }
            return json.get(jsonKey).asText();
        }
        if ("ssm".equals(service) || "ssm-secure".equals(service)) {
            if ("ssm-secure".equals(service) && !allowSsmSecure) {
                throw new AwsException("ValidationError",
                        "ssm-secure dynamic references are supported only for MasterUserPassword "
                                + "on AWS::RDS::DBInstance and AWS::RDS::DBCluster", 400);
            }
            Matcher reference = SSM_DYNAMIC_REF_BODY.matcher(body);
            if (!reference.matches()) {
                throw invalidSsmDynamicReference();
            }
            String parameterName = reference.group(1);
            String version = reference.group(2);
            if (version != null) {
                long wantedVersion;
                try {
                    wantedVersion = Long.parseLong(version);
                } catch (NumberFormatException e) {
                    throw new AwsException("ValidationError",
                            "SSM parameter version must be a positive integer: " + version, 400);
                }
                if (wantedVersion < 1) {
                    throw new AwsException("ValidationError",
                            "SSM parameter version must be a positive integer: " + version, 400);
                }
                ParameterHistory parameter = ssmService.getParameterHistory(parameterName, region).stream()
                        .filter(h -> h.getVersion() == wantedVersion)
                        .findFirst()
                        .orElseThrow(() -> new AwsException(
                                "ParameterVersionNotFound",
                                "Parameter version " + wantedVersion + " not found.", 400));
                return validatedSsmParameterValue(
                        service, parameterName, parameter.getType(), parameter.getValue());
            }
            Parameter parameter = ssmService.getParameter(parameterName, region);
            return validatedSsmParameterValue(
                    service, parameterName, parameter.getType(), parameter.getValue());
        }
        // Other dynamic-reference services are not resolved here; leave verbatim.
        return "{{resolve:" + service + ":" + body + "}}";
    }

    private static boolean isValidSecretsManagerSecretId(String secretId) {
        if (secretId == null || secretId.isBlank()) {
            return false;
        }
        if (!secretId.contains(":")) {
            return true;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(secretId);
            String resource = arn.resource();
            return "secretsmanager".equals(arn.service())
                    && !arn.region().isBlank()
                    && !arn.accountId().isBlank()
                    && resource.startsWith("secret:")
                    && resource.length() > "secret:".length()
                    && !resource.substring("secret:".length()).contains(":");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static AwsException invalidSecretsManagerDynamicReference() {
        return new AwsException("ValidationError",
                "Invalid Secrets Manager dynamic reference", 400);
    }

    private static void rejectUnclosedDynamicReference(String value) {
        if (value.contains("{{resolve:secretsmanager:")) {
            throw invalidSecretsManagerDynamicReference();
        }
        if (value.contains("{{resolve:ssm:") || value.contains("{{resolve:ssm-secure:")) {
            throw invalidSsmDynamicReference();
        }
    }

    private static String validatedSsmParameterValue(
            String service, String parameterName, String parameterType, String value) {
        boolean validType = "ssm-secure".equals(service)
                ? "SecureString".equals(parameterType)
                : "String".equals(parameterType) || "StringList".equals(parameterType);
        if (!validType) {
            String expectedType = "ssm-secure".equals(service)
                    ? "SecureString"
                    : "String or StringList";
            throw new AwsException("ValidationError",
                    "SSM parameter " + parameterName + " must be type " + expectedType
                            + " for an " + service + " dynamic reference", 400);
        }
        return value;
    }

    private static AwsException invalidSsmDynamicReference() {
        return new AwsException("ValidationError",
                "Invalid SSM dynamic reference", 400);
    }
}
