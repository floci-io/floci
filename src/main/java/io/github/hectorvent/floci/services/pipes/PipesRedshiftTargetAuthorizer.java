package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether the role of a pipe may submit the SQL of its Redshift Data API target. Pipes assumes
 * the pipe's role, so the role has to trust {@code pipes.amazonaws.com} (with a condition on
 * {@code aws:SourceArn} naming the pipe, if it has one), and its identity policies, permissions boundary
 * and SCPs have to allow every {@link Permission} the submission needs. Only active with IAM enforcement
 * on, as for every other API call. A role of another account is refused, because the pipe's account does
 * not own it.
 */
@ApplicationScoped
public class PipesRedshiftTargetAuthorizer {

    private static final String PIPES_PRINCIPAL = ServicePrincipals.of("pipes");

    private final EmulatorConfig config;
    private final ServiceRoleAuthorizer authorizer;

    @Inject
    public PipesRedshiftTargetAuthorizer(EmulatorConfig config, ServiceRoleAuthorizer authorizer) {
        this.config = config;
        this.authorizer = authorizer;
    }

    /**
     * Returns why {@code roleArn} may not run the target, or nothing when it may.
     *
     * @param accountId the account that owns the pipe
     * @param pipeArn the pipe, which a trust policy may require in {@code aws:SourceArn}
     * @param permissions what the submission needs, each of which has to be allowed
     */
    public Optional<String> refusal(String roleArn, String accountId, String pipeArn,
                                    List<Permission> permissions) {
        if (!config.services().iam().enforcementEnabled()) {
            return Optional.empty();
        }
        if (roleArn == null || roleArn.isBlank()) {
            return Optional.of("A Redshift target requires an IAM role, and this pipe names none.");
        }
        return authorizer.refusal(roleArn, accountId, PIPES_PRINCIPAL, pipeArn, permissions);
    }
}
