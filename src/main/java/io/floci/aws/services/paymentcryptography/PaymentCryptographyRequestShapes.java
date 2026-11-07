package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestConstraintCheck;

/**
 * Checks a Payment Cryptography request against the constraints vendored in
 * {@code aws/payment-cryptography-request-shapes.json} by
 * {@code tools/aws/regen_payment_cryptography_shapes.py}.
 *
 * <p>The patterns are what settle which key a request may name: {@code KeyIdentifier} takes the ARN
 * of a key, the ARN of an alias or the name of an alias, while {@code KeyArn} and {@code ResourceArn}
 * take the ARN of a key alone. A violation is a {@code ValidationException}, with the member's path
 * from the request's top level.
 */
final class PaymentCryptographyRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/payment-cryptography-request-shapes.json", "Payment Cryptography request shapes",
            PaymentCryptographyShapeErrors.MESSAGES);

    private PaymentCryptographyRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }
}
