package io.floci.aws.services.kms.model;

import io.floci.aws.core.common.SmithyValidationErrors;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum KmsKeyUsage {
    SIGN_VERIFY,
    ENCRYPT_DECRYPT,
    GENERATE_VERIFY_MAC,
    KEY_AGREEMENT;

    public static KmsKeyUsage fromString(String usage) {
        try {
            return KmsKeyUsage.valueOf(usage);
        } catch (IllegalArgumentException _) {
            throw SmithyValidationErrors.validationException(
                    "Value '" + usage + "' at 'KeyUsage' failed to satisfy constraint");
        }
    }
}
