package io.floci.aws.services.securityhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;

import java.util.List;

/** Checks the modeled part of Security Hub findings requests. */
final class SecurityHubRequestShapes {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/securityhub-request-shapes.json", "Security Hub request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return invalid(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
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
        ObjectNode modeled = MAPPER.createObjectNode();
        for (String field : List.of("RecordState", "Compliance", "Severity", "FindingProviderFields",
                "ProductFields", "RelatedFindings")) {
            if (finding.has(field)) {
                modeled.set(field, finding.get(field));
            }
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
