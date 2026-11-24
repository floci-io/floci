package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * S3 auth enforcement, IAM enforcement and the global bucket namespace together, for the classes
 * that need all three, and the settings of a few other classes that share the application.
 * {@link S3EnforceAuthProfile} covers the narrower case. Quarkus builds the application once per
 * distinct profile class, and keeps every one it built for the rest of the test JVM, so they share
 * this one rather than each declaring their own.
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
 *   <li>{@code floci.services.rds.mock=true} and {@code floci.services.docdb.mock=true} keep RDS and
 *   DocumentDB from starting database containers. {@code DocDbClusterSettingsIntegrationTest},
 *   {@code RdsFamilyListingIntegrationTest} and {@code SharedClusterIdentifierIntegrationTest},
 *   which span the two engines, rely on them.</li>
 *   <li>{@code floci.services.eks.mock=false} runs EKS clusters as real k3s containers, and
 *   {@code floci.services.eks.irsa-signing-key=true} has k3s sign projected service account tokens
 *   with the cluster's OIDC keypair. {@code EksIrsaDockerNightlyIntegrationTest} relies on them, and on IAM
 *   enforcement to refuse a mismatched subject or an untrusted issuer.</li>
 * </ul>
 *
 * <p>Each override is inert for the classes it does not name, which pass with it off. S3 auth
 * enforcement only adds refusals, and every refusal those classes assert comes from IAM as well.
 * {@code GeneratedPreSignedUrlAuthEnforcementIntegrationTest} and
 * {@code S3PresignedPostAuthEnforcementIntegrationTest} reach each bucket only from the account
 * that owns it, so the namespace never comes into play for them.
 *
 * <p>The RDS, DocumentDB and EKS settings are inert for every other class here: each is read by a
 * single service, and no other class calls it. The DocumentDB classes sign every request with the
 * {@code test} root stand-in and never act as a role, so IAM enforcement passes all their requests,
 * and none of them calls S3, so the S3 settings never reach them.
 *
 * <p>A class joins only if no other class here calls the service its setting changes, and if it
 * either relies on IAM enforcement or signs every request as the {@code test} root stand-in without
 * a role. An unsigned request is refused here.
 */
public class S3IamEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.s3.enforce-auth", "true",
                "floci.services.iam.enforcement-enabled", "true",
                "floci.services.s3.global-bucket-namespace", "true",
                "floci.services.rds.mock", "true",
                "floci.services.docdb.mock", "true",
                "floci.services.eks.mock", "false",
                "floci.services.eks.irsa-signing-key", "true");
    }
}
