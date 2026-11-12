package io.floci.aws.services.securityhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.VendoredResources;

import java.util.List;

/** Checks the modeled part of Security Hub findings requests. */
final class SecurityHubRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.of(
            VendoredResources.read("aws/securityhub-request-shapes.json", "Security Hub request shapes", input -> {
                JsonNode model = new ObjectMapper().readTree(input);
                ObjectNode nonEmptyString = (ObjectNode) model.path("shapes").path("NonEmptyString");
                // Java's dot excludes line breaks, but Security Hub accepts multiline non-blank text.
                nonEmptyString.put("pattern", "(?s:" + nonEmptyString.path("pattern").asText() + ")");
                return model;
            }),
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return invalid(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, Number size) {
                    if ("BatchImportFindings.Findings".equals(path)) {
                        return invalid("Findings must contain between " + min + " and " + max + " findings.");
                    }
                    return invalid(path + " must be between " + min + " and " + max + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path + " does not match the modeled pattern.");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalid(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return invalid(path + " is required.");
                }
            });

    private SecurityHubRequestShapes() {
    }

    static void checkFindingsSize(int size) {
        CHECK.checkListSize("BatchImportFindings", "Findings", size);
    }

    static void checkFinding(JsonNode finding) {
        JsonNode modeled = finding.deepCopy();
        if (modeled instanceof ObjectNode object) {
            object.remove("VerificationState");
        }
        CHECK.checkShape("AwsSecurityFinding", modeled);
    }

    static void checkInitialVerificationState(JsonNode value) {
        CHECK.checkShape("VerificationState", value);
    }

    static void checkGetFindings(JsonNode request) {
        CHECK.check("GetFindings", request);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }
}
