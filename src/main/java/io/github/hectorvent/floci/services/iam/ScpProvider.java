package io.github.hectorvent.floci.services.iam;

import java.util.List;

/**
 * Supplies the effective service control policies for an account, one list of policy
 * documents per organization level (root, each OU on the path, then the account).
 *
 * <p>Implemented by the Organizations service; consumed lazily by
 * {@code IamEnforcementFilter} via {@code Instance<ScpProvider>} so IAM never depends on
 * Organizations directly (Organizations already depends on IAM for role provisioning).</p>
 */
public interface ScpProvider {

    /**
     * @return the effective SCP levels for the account, or {@code null} when SCPs don't
     *         apply: SCP enforcement is disabled, the account is not in an organization,
     *         the account is the organization's management account (exempt in AWS), or
     *         the SCP policy type is not enabled on the root.
     */
    List<List<String>> effectiveScpLevels(String accountId);
}
