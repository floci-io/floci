package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProviders;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Decides a call one AWS service makes to another's resource on its own behalf, such as SES
 * publishing an event notification to an SNS topic. These calls never pass through
 * {@link IamEnforcementFilter}: no IAM principal makes them, so no identity policy, session policy,
 * boundary or SCP applies, and the target's resource policy decides alone. It has to allow the
 * calling service principal, under the conditions the guides tell owners to write against the
 * confused deputy problem: {@code aws:SourceArn} and {@code aws:SourceAccount}, the resource whose
 * configuration triggered the call.
 *
 * <p>The policy comes from the same {@link ResourcePolicyProvider}s the filter reads, picked by the
 * target ARN's service. A target no provider knows has no policy, and a policy that says nothing
 * denies, as on AWS.
 *
 * <p>Only active with IAM enforcement on. With it off, every call is allowed, as every API call is.
 * The decision is returned rather than thrown, because what a refusal means is the caller's to
 * decide: a notification that is not delivered, a delivery that is retried, or an error.
 */
@ApplicationScoped
public class ServiceCallAuthorizer {

    private static final Logger LOG = Logger.getLogger(ServiceCallAuthorizer.class);

    private final EmulatorConfig config;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ResourcePolicyProvider> resourcePolicyProviders;

    @Inject
    public ServiceCallAuthorizer(EmulatorConfig config, IamPolicyEvaluator evaluator,
                                 Instance<ResourcePolicyProvider> resourcePolicyProviders) {
        this.config = config;
        this.evaluator = evaluator;
        this.resourcePolicyProviders = resourcePolicyProviders;
    }

    /**
     * Decides whether {@code service} may perform {@code action} on {@code resourceArn}.
     *
     * @param service       the calling service, such as {@code ses}; its principal is
     *                      {@code ServicePrincipals.of(service)}
     * @param action        the IAM action, such as {@code sns:Publish}
     * @param resourceArn   the target resource
     * @param sourceArn     the resource whose configuration triggered the call, or null
     * @param sourceAccount the account that owns it, or null
     */
    public Decision authorize(String service, String action, String resourceArn,
                              String sourceArn, String sourceAccount) {
        if (!config.services().iam().enforcementEnabled()) {
            return Decision.ALLOW;
        }
        String servicePrincipal = ServicePrincipals.of(service);
        String targetService = AwsArnUtils.isArn(resourceArn) ? AwsArnUtils.parse(resourceArn).service() : null;
        List<ResourcePolicyProvider.ResourcePolicy> policies =
                ResourcePolicyProviders.policiesFor(resourcePolicyProviders, targetService, resourceArn);
        String ownerAccountId = policies.isEmpty() ? null : policies.getFirst().ownerAccountId();
        List<String> documents = policies.stream()
                .map(ResourcePolicyProvider.ResourcePolicy::policyDocument)
                .filter(document -> document != null && !document.isBlank())
                .toList();
        Map<String, List<String>> context = IamConditionContextResolver.serviceCallContext(
                servicePrincipal, resourceArn, ownerAccountId, sourceArn, sourceAccount);
        Decision decision = evaluator.evaluateServicePrincipal(
                documents.isEmpty() ? null : documents, servicePrincipal, action, resourceArn, context);
        if (decision == Decision.DENY) {
            LOG.infov("IAM enforcement DENY: service={0} action={1} resource={2} source={3}",
                    servicePrincipal, action, resourceArn, sourceArn);
        }
        return decision;
    }
}
