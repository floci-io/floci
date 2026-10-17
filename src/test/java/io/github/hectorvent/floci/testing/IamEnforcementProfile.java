package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * IAM policy enforcement, with service control policy enforcement next to it, for the classes
 * that test what an IAM identity may do. Quarkus builds the application once per distinct
 * profile class, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.iam.enforcement-enabled=true} evaluates every request against the
 *   caller's IAM policies. Every class here asserts a decision it makes, except
 *   {@code EnforcementFixturesIntegrationTest} and {@code StsPresignedIdentityIntegrationTest},
 *   which pass with it off.</li>
 *   <li>{@code floci.services.organizations.scp-enforcement-enabled=true} caps a member account's
 *   permissions with the SCPs its organization attaches.
 *   {@code ScpEnforcementLeaveOrganizationIntegrationTest} and
 *   {@code DynamoDbTransactionPolicyBoundaryIntegrationTest} rely on it: each creates an
 *   organization, enables SCPs on its root and attaches a deny that reaches a member account.</li>
 * </ul>
 *
 * <p>SCP enforcement is inert for every other class. An SCP only applies to a member account of an
 * organization whose root has SCPs enabled, and the management account is exempt, as in AWS. No
 * other class creates an organization, so the SCP lookup finds no ceiling for any account they use
 * and the decision is the one IAM enforcement alone makes.
 */
public class IamEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.iam.enforcement-enabled", "true",
                "floci.services.organizations.scp-enforcement-enabled", "true");
    }
}
