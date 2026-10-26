package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * S3 auth enforcement, IAM enforcement and the global bucket namespace together, for the classes
 * that need all three. {@link S3EnforceAuthProfile} covers the narrower case. Quarkus builds the
 * application once per distinct profile class, so they share this one rather than each declaring
 * their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.s3.enforce-auth=true} makes S3 verify every signature and run its own
 *   access checks, bucket policies among them, beside IAM's.
 *   {@code S3BucketPolicyEnforcementIntegrationTest},
 *   {@code S3BucketCollisionIamEnforcementIntegrationTest},
 *   {@code S3CopyObjectSourcePermissionIntegrationTest},
 *   {@code S3DeleteObjectsSignedIamEnforcementIntegrationTest} and
 *   {@code S3PresignedPostAuthEnforcementIntegrationTest} rely on it.</li>
 *   <li>{@code floci.services.iam.enforcement-enabled=true} evaluates every request against the
 *   caller's IAM policies. Every class relies on it except
 *   {@code ResourcePolicyPrincipalTypeEnforcementIntegrationTest}, whose cross-account caller
 *   S3's own bucket policy check refuses as well.</li>
 *   <li>{@code floci.services.s3.global-bucket-namespace=true} resolves a bucket name in the
 *   account that owns it, whoever calls.
 *   {@code ResourcePolicyPrincipalTypeEnforcementIntegrationTest},
 *   {@code S3BucketPolicyEnforcementIntegrationTest},
 *   {@code S3BucketCollisionIamEnforcementIntegrationTest},
 *   {@code S3CopyObjectSourcePermissionIntegrationTest} and
 *   {@code S3DeleteObjectsSignedIamEnforcementIntegrationTest} call another account's bucket, and
 *   {@code StsIssuedSessionEnforcementIntegrationTest} reads one anonymously, which Floci serves as
 *   the default account.</li>
 * </ul>
 *
 * <p>Each override is inert for the classes it does not name, which pass with it off. S3 auth
 * enforcement only adds refusals, and every refusal those classes assert comes from IAM as well.
 * {@code GeneratedPreSignedUrlAuthEnforcementIntegrationTest} and
 * {@code S3PresignedPostAuthEnforcementIntegrationTest} reach each bucket only from the account
 * that owns it, so the namespace never comes into play for them.
 */
public class S3IamEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.s3.enforce-auth", "true",
                "floci.services.iam.enforcement-enabled", "true",
                "floci.services.s3.global-bucket-namespace", "true");
    }
}
