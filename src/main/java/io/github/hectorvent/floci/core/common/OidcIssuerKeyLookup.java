package io.github.hectorvent.floci.core.common;

import java.security.interfaces.RSAPublicKey;
import java.util.Optional;

/**
 * Port that maps an OIDC issuer URL to the public key that signs its tokens.
 *
 * <p>Declared in {@code core.common} so the STS handler (which lives in
 * {@code services.iam}) can verify {@code sts:AssumeRoleWithWebIdentity} tokens minted for an
 * EKS cluster's IRSA issuer without {@code services.iam} depending on {@code services.eks}.
 *
 * <p>An empty result means the issuer is unknown to Floci. Callers treat that as "not a
 * Floci-issued token" and fall back to permissive handling rather than rejecting, so
 * third-party web-identity tokens keep working.
 */
public interface OidcIssuerKeyLookup {

    /**
     * Returns the RSA public key for {@code issuer}, or {@link Optional#empty()} if no
     * resource known to Floci issues tokens under that issuer URL.
     */
    Optional<RSAPublicKey> findVerificationKey(String issuer);
}
