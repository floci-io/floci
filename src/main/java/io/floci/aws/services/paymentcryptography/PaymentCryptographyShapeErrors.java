package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;

import java.util.List;

/**
 * How a Payment Cryptography request that breaks a constraint is refused.
 *
 * <p>The control plane and the data plane are checked by the same machinery and are worded the
 * same way, so the wording is here rather than in each of them. The path a violation names is the
 * member's path below the operation, with the list elements folded into their list.
 */
final class PaymentCryptographyShapeErrors {

    /** The wording every constraint violation of this service is refused with. */
    static final RequestConstraintCheck.Messages MESSAGES = new RequestConstraintCheck.Messages() {
        @Override
        public AwsException type(String path, String type) {
            return invalid(path, "Member must be a " + type);
        }

        @Override
        public AwsException bounds(String path, String type, Long min, Long max, long size) {
            String measure = "integer".equals(type) || "long".equals(type) ? "value" : "length";
            return min != null && size < min
                    ? invalid(path, "Member must have " + measure + " greater than or equal to " + min)
                    : invalid(path, "Member must have " + measure + " less than or equal to " + max);
        }

        @Override
        public AwsException pattern(String path, String pattern) {
            return invalid(path, "must satisfy regular expression '" + pattern + "'");
        }

        @Override
        public AwsException enumValue(String path, List<String> values) {
            return invalid(path, "Member must satisfy enum value set: " + values);
        }

        @Override
        public AwsException required(String path) {
            return invalid(path, "Member must not be null");
        }
    };

    private PaymentCryptographyShapeErrors() {
    }

    /** The violation, as the service refuses it. */
    static AwsException invalid(String path, String rule) {
        int dot = path.indexOf('.');
        String member = (dot < 0 ? "" : path.substring(dot + 1)).replace("[]", "");
        return new AwsException("ValidationException",
                "Invalid parameter at '" + member + "' failed to satisfy constraint: '" + rule + "'", 400);
    }
}
