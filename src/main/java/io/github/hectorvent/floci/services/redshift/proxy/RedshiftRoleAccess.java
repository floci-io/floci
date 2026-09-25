package io.github.hectorvent.floci.services.redshift.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Role resolution and S3 authorization shared by {@code COPY}, {@code UNLOAD}, and Redshift
 * Spectrum: validates an {@code IAM_ROLE} ARN against the cluster and Redshift's trust policy,
 * mints a short-lived session, and evaluates the role's identity policy for one S3 action.
 *
 * <p>Extracted from {@link S3CopySimulator} so the Spectrum package shares one implementation.
 */
public final class RedshiftRoleAccess {

    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String UPPER_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String SECRET_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    /** COPY/UNLOAD is synchronous end-to-end; this only needs to outlive one statement. */
    private static final Duration ROLE_SESSION_TTL = Duration.ofMinutes(5);
    /** Spectrum streams rows lazily and authorizes each object as it is read, so one query can outlive a COPY. */
    public static final Duration STREAMING_ROLE_SESSION_TTL = Duration.ofMinutes(30);

    // S3Service's signed-request authorization only checks the bucket's resource policy: for a
    // genuine HTTP request the identity-policy gate already happened upstream in
    // IamEnforcementFilter before the request ever reached S3Service. This in-process call never
    // goes through that filter, so the role's identity policy is evaluated here explicitly.
    // Stateless (only needs an ObjectMapper), so a local instance avoids threading a new
    // dependency through the whole proxy chain for this one check.
    private static final IamPolicyEvaluator ROLE_POLICY_EVALUATOR = new IamPolicyEvaluator(new ObjectMapper());
    private static final AssumeRolePolicyEvaluator ROLE_TRUST_POLICY_EVALUATOR =
            new AssumeRolePolicyEvaluator(new ObjectMapper());

    private RedshiftRoleAccess() {
    }

    public record RoleSession(String accessKeyId, String sessionToken) {
    }

    /**
     * Validates the role ARN, its account and Redshift trust policy, then mints a short-lived session.
     */
    public static RoleSession resolveRoleSession(String iamRoleArn, IamService iamService,
                                          String clusterAccountId, List<String> associatedRoleArns) {
        return resolveRoleSession(iamRoleArn, iamService, clusterAccountId, associatedRoleArns, ROLE_SESSION_TTL);
    }

    public static RoleSession resolveRoleSession(String iamRoleArn, IamService iamService,
                                          String clusterAccountId, List<String> associatedRoleArns,
                                          Duration sessionTtl) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(iamRoleArn);
        } catch (IllegalArgumentException e) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: malformed ARN", e);
        }
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: not an IAM role ARN", null);
        }
        if (clusterAccountId != null && !clusterAccountId.equals(parsed.accountId())) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: cross-account role ARNs are not supported", null);
        }
        if (clusterAccountId != null && associatedRoleArns != null && !associatedRoleArns.contains(iamRoleArn)) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn
                            + "' could not be assumed: role is not associated with the Redshift cluster", null);
        }
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        Optional<IamRole> role = iamService.findRole(parsed.accountId(), roleName);
        if (role.isEmpty()) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: role does not exist", null);
        }
        if (clusterAccountId != null
                && !ROLE_TRUST_POLICY_EVALUATOR.allowsService(
                        role.get().getAssumeRolePolicyDocument(), servicePrincipal(parsed.partition()))) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: trust policy does not allow "
                            + servicePrincipal(parsed.partition()),
                    null);
        }

        String accessKeyId = "ASIA" + randomString(UPPER_ALPHANUMERIC, 16);
        // A session token is required: IamService.findSecretKey(accessKeyId, sessionToken) treats a
        // null token on either side as a non-match, so a tokenless session can never be recognised
        // as a known access key and every signed authorization call would fail closed.
        String sessionToken = randomString(SECRET_CHARACTERS, 200);
        iamService.registerSessionForAccount(parsed.accountId(), accessKeyId,
                randomString(SECRET_CHARACTERS, 40), sessionToken, iamRoleArn,
                Instant.now().plus(sessionTtl), null);
        return new RoleSession(accessKeyId, sessionToken);
    }

    public static void releaseRoleSession(RoleSession roleSession, String iamRoleArn, IamService iamService) {
        if (roleSession == null) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(iamRoleArn);
        iamService.unregisterSession(parsed.accountId(), roleSession.accessKeyId());
    }

    /**
     * Evaluates the role's identity-based policy for one S3 action, skipped entirely when
     * {@code FLOCI_SERVICES_S3_ENFORCE_AUTH} is off (matching the anonymous path's behavior).
     */
    public static void authorizeRoleAction(S3Service s3, IamService iamService, String roleArn,
                                    String action, String resourceArn) {
        authorizeRoleAction(s3, iamService, roleArn, action, resourceArn, Map.of());
    }

    public static void authorizeRoleAction(S3Service s3, IamService iamService, String roleArn,
                                    String action, String resourceArn, Map<String, List<String>> conditionContext) {
        if (!s3.isAuthEnforced()) {
            return;
        }
        CallerContext caller;
        try {
            caller = iamService.resolvePrincipalContext(roleArn);
        } catch (AwsException e) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + roleArn + "' could not be assumed: " + e.getMessage(), e);
        }
        IamPolicyEvaluator.SimulationDecision decision =
                ROLE_POLICY_EVALUATOR.simulatePrincipalPolicy(caller, action, resourceArn, conditionContext);
        if (decision != IamPolicyEvaluator.SimulationDecision.ALLOWED) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for IAM Role '" + roleArn + "': " + action + " on " + resourceArn, null);
        }
    }

    /**
     * Authorizes {@code s3:ListBucket} as the role's signed session: the identity policy first, then
     * the bucket policy through the signed-request path COPY uses, so a bucket-policy deny applies
     * even when the identity policy allows the action. {@code prefix} feeds the {@code s3:prefix}
     * condition key an identity policy may use to constrain the listing.
     */
    public static void authorizeRoleList(S3Service s3, IamService iamService, RoleSession roleSession,
                                         String roleArn, String bucket, String prefix) {
        authorizeRoleAction(s3, iamService, roleArn, "s3:ListBucket", bucketArn(roleArn, bucket),
                Map.of("s3:prefix", List.of(prefix == null ? "" : prefix)));
        try {
            s3.authorizeSignedListBucket(roleSession.accessKeyId(), roleSession.sessionToken(), bucket);
        } catch (AwsException e) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + bucket, e);
        }
    }

    /** {@code s3:GetObject} counterpart of {@link #authorizeRoleList}. */
    public static void authorizeRoleRead(S3Service s3, IamService iamService, RoleSession roleSession,
                                         String roleArn, String bucket, String key) {
        authorizeRoleAction(s3, iamService, roleArn, "s3:GetObject", objectArn(roleArn, bucket, key));
        try {
            s3.authorizeSignedGetObject(roleSession.accessKeyId(), roleSession.sessionToken(), bucket, key);
        } catch (AwsException e) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + bucket + "/" + key, e);
        }
    }

    public static String bucketArn(String roleArn, String bucket) {
        AwsArnUtils.Arn role = AwsArnUtils.parse(roleArn);
        return new AwsArnUtils.Arn(role.partition(), "s3", "", "", bucket).toString();
    }

    public static String objectArn(String roleArn, String bucket, String key) {
        AwsArnUtils.Arn role = AwsArnUtils.parse(roleArn);
        return new AwsArnUtils.Arn(role.partition(), "s3", "", "", bucket + "/" + key).toString();
    }

    private static String servicePrincipal(String partition) {
        return "redshift." + AwsRegions.dnsSuffixForPartition(partition);
    }

    private static String randomString(String characters, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(characters.charAt(SECURE_RANDOM.nextInt(characters.length())));
        }
        return value.toString();
    }
}
