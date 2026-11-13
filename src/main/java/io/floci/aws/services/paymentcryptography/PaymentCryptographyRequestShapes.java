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
 *
 * <p>The vendored file holds every operation the model names, so an operation the service serves
 * is checked without anyone listing it. An operation the model does not name is not checked, and
 * the service refuses it as the operation it does not serve.
 *
 * <p>Only the operations the service serves are checked: an operation the model names and the
 * service does not serve is refused as the unknown operation it is, whatever its body says.
 */
final class PaymentCryptographyRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/payment-cryptography-request-shapes.json", "Payment Cryptography request shapes",
            RequestConstraintCheck.standardMessages("ValidationException"));

    private PaymentCryptographyRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        if (!CHECK.holds(operation)) {
            return;
        }
        CHECK.check(operation, request);
    }
}
