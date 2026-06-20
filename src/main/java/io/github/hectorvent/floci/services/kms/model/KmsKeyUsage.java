package io.github.hectorvent.floci.services.kms.model;

import io.github.hectorvent.floci.core.common.AwsException;
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
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + usage + "' at 'KeyUsage' failed to satisfy constraint", 400);
        }
    }
}
