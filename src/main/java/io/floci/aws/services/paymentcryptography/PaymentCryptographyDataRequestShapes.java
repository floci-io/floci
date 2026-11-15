package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestConstraintCheck;

/**
 * Checks a Payment Cryptography data plane request against the constraints vendored in
 * {@code aws/payment-cryptography-data-request-shapes.json} by
 * {@code tools/aws/regen_payment_cryptography_data_shapes.py}.
 *
 * <p>The patterns are what settle which key a request may name: {@code KeyIdentifier} takes the ARN
 * of a key, the ARN of an alias or the name of an alias, and the identifier of a key on its own
 * names none of them. A violation is a {@code ValidationException}, with the member's path from
 * the request's top level.
 */
final class PaymentCryptographyDataRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/payment-cryptography-data-request-shapes.json",
            "Payment Cryptography data plane request shapes",
            RequestConstraintCheck.standardMessages("ValidationException"));

    private PaymentCryptographyDataRequestShapes() {
    }

    /**
     * Checks a request of an operation against the constraints the model states for it.
     *
     * @param operation the operation the request is
     * @param request the request
     */
    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }
}
