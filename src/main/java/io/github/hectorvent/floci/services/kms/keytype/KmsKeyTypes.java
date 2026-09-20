package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;

import java.security.SecureRandom;

public final class KmsKeyTypes {

    private final SymmetricKeyType symmetric;
    private final KmsKeyType hmac;
    private final KmsKeyType rsa;
    private final KmsKeyType eccNist;
    private final KmsKeyType eccSecgP256k1;
    private final KmsKeyType ed25519;
    private final KmsKeyType mlDsa;
    private final KmsKeyType sm2;

    public KmsKeyTypes(SecureRandom random) {
        this.symmetric = new SymmetricKeyType(random);
        this.hmac = new HmacKeyType(random);
        this.rsa = new RsaKeyType(random);
        this.eccNist = new EccNistKeyType();
        this.eccSecgP256k1 = new EccSecgP256k1KeyType(random);
        this.ed25519 = new Ed25519KeyType();
        this.mlDsa = new MlDsaKeyType();
        this.sm2 = new Sm2KeyType(random);
    }

    public SymmetricKeyType symmetric() {
        return symmetric;
    }

    public KmsKeyType of(KmsKeySpec spec) {
        return switch (spec.getKeyType()) {
            case SYMMETRIC -> symmetric;
            case HMAC -> hmac;
            case RSA -> rsa;
            case ECC -> spec == KmsKeySpec.ECC_SECG_P256K1 ? eccSecgP256k1 : eccNist;
            case ED25519 -> ed25519;
            case ML_DSA -> mlDsa;
            case SM2 -> sm2;
        };
    }
}
