package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Signature validation and IAM enforcement together, with S3 auth enforcement next to them, for
 * the classes that test how a signed request is authenticated before IAM decides it. Quarkus builds
 * the application once per distinct profile class, so they share this one rather than each
 * declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.auth.validate-signatures=true} verifies every SigV4 signature, in the header
 *   or the query string, and refuses a forged or unknown credential. Every class relies on it.</li>
 *   <li>{@code floci.services.iam.enforcement-enabled=true} evaluates every request against the
 *   caller's IAM policies. {@code StsFederationCallerEnforcementIntegrationTest},
 *   {@code CloudWatchQueryGetSignatureIntegrationTest} and
 *   {@code CloudWatchJsonTargetSignatureIntegrationTest} rely on it, for the refusals their IAM
 *   users' policies decide and, in the last, for refusing unsigned calls;
 *   {@code StsPresignedSignatureIntegrationTest} passes with it off.</li>
 *   <li>{@code floci.services.s3.enforce-auth=true} makes S3 run its own access checks, bucket
 *   policies among them, on top of the signature check. No class fails with it off. It stays so
 *   that the signed S3 request in {@code CloudWatchQueryGetSignatureIntegrationTest}, which must
 *   reach S3 rather than CloudWatch, still runs under those checks, as that class was written to.
 *   It only acts on requests routed to S3, and no other class sends one.</li>
 * </ul>
 */
public class SignedIamEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.auth.validate-signatures", "true",
                "floci.services.iam.enforcement-enabled", "true",
                "floci.services.s3.enforce-auth", "true");
    }
}
