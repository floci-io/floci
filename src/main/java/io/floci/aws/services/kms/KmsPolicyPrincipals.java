package io.floci.aws.services.kms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;

import java.util.List;
import java.util.function.Predicate;

final class KmsPolicyPrincipals {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KmsPolicyPrincipals() {
    }

    static void requireExisting(String policy, String account, Predicate<String> exists) {
        if (policy == null) {
            return;
        }
        JsonNode document;
        try {
            document = MAPPER.readTree(policy);
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
        if (document == null) {
            throw invalid();
        }
        JsonNode statements = document.path("Statement");
        Iterable<JsonNode> entries = statements.isArray() ? statements : List.of(statements);
        for (JsonNode statement : entries) {
            JsonNode aws = statement.path("Principal").path("AWS");
            Iterable<JsonNode> principals = aws.isArray() ? aws : List.of(aws);
            for (JsonNode principal : principals) {
                String value = principal.asText();
                if (!AwsArnUtils.isArn(value) || value.contains("*") || value.contains("?")) {
                    continue;
                }
                AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
                if ("iam".equals(arn.service()) && account.equals(arn.accountId())
                        && (arn.resource().startsWith("role/") || arn.resource().startsWith("user/"))
                        && !exists.test(value)) {
                    throw invalid();
                }
            }
        }
    }

    private static AwsException invalid() {
        return new AwsException("MalformedPolicyDocumentException",
                "Policy contains a statement with one or more invalid principals.", 400);
    }
}
