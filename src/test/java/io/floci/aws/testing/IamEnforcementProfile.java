package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * IAM policy enforcement, with service control policy enforcement next to it, for the classes
 * that test what an IAM identity may do, and the settings of a few other classes that share the
 * application. Quarkus builds the application once per distinct profile class, and keeps every one
 * it built for the rest of the test JVM, so they share this one rather than each declaring their
 * own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.iam.enforcement-enabled=true} evaluates every request against the
 *   caller's IAM policies. Every class here asserts a decision it makes, except
 *   {@code EnforcementFixturesIntegrationTest} and {@code StsPresignedIdentityIntegrationTest},
 *   which pass with it off.</li>
 *   <li>{@code floci.services.organizations.scp-enforcement-enabled=true} caps a member account's
 *   permissions with the SCPs its organization attaches.
 *   {@code ScpEnforcementLeaveOrganizationIntegrationTest},
 *   {@code ScpEnforcementOrganizationContextIntegrationTest} and
 *   {@code DynamoDbTransactionPolicyBoundaryIntegrationTest} rely on it: each creates an
 *   organization, enables SCPs on its root and attaches a deny that reaches a member account.</li>
 *   <li>{@code floci.partitions.allow-unknown-regions=true} gives a region label no partition
 *   publishes a namespace of its own, where it is otherwise refused.
 *   {@code LogsUnknownRegionAuthorizationIntegrationTest} relies on it, to check how such a region
 *   is authorized.</li>
 *   <li>{@code floci.services.docdb.mock=true} keeps DocumentDB from starting database containers,
 *   and deliberately leaves {@code floci.services.rds.mock} alone, so the DocumentDB classes
 *   exercise the RDS paths they share unmocked. {@code DocDbClusterSnapshotIntegrationTest},
 *   {@code DocDbLegacyRecordArnIntegrationTest}, {@code DocDbRegionScopedIdentifierIntegrationTest}
 *   and {@code DocDbTagConcurrencyTest} rely on it.</li>
 * </ul>
 *
 * <p>SCP enforcement is inert for every other class. An SCP only applies to a member account of an
 * organization whose root has SCPs enabled, and the management account is exempt, as in AWS. The
 * three classes above are the only ones here that create an organization, each under a management
 * account of its own and removing it as it ends, so the SCP lookup finds no ceiling for any account
 * the others use and their decisions are the ones IAM enforcement alone makes.
 *
 * <p>The region and DocumentDB settings are inert for the other classes: no other class here sends a
 * region no partition publishes, and none calls DocumentDB. The DocumentDB classes sign every request
 * with the {@code test} root stand-in, or call the service directly, and never act as a role, so
 * IAM enforcement passes them through.
 *
 * <p>A class joins if it tests what IAM enforcement decides, or if it signs every request as the
 * {@code test} root stand-in without a role and no other class here calls the service its own
 * setting changes. An unsigned request is refused here. A profile that extends this one starts from
 * {@link #enforcementOverrides()}, so the other classes' settings stay with them.
 */
public class IamEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(enforcementOverrides());
        overrides.put("floci.partitions.allow-unknown-regions", "true");
        overrides.put("floci.services.docdb.mock", "true");
        return overrides;
    }

    /** IAM and SCP enforcement alone, for a profile that extends this one for classes of its own. */
    public static Map<String, String> enforcementOverrides() {
        return Map.of(
                "floci.services.iam.enforcement-enabled", "true",
                "floci.services.organizations.scp-enforcement-enabled", "true");
    }
}
