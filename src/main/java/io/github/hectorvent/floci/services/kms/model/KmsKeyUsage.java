package io.github.hectorvent.floci.services.kms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum KmsKeyUsage {
    SIGN_VERIFY,
    ENCRYPT_DECRYPT,
    GENERATE_VERIFY_MAC,
    KEY_AGREEMENT,
}
