package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;

/**
 * Decides how long a custom-resource ResponseURL callback may stay idle, by recognising the CDK
 * Provider framework's {@code framework.onEvent} Lambda from its environment.
 */
@ApplicationScoped
public class ProviderFrameworkDetector {

    private static final Logger LOG = Logger.getLogger(ProviderFrameworkDetector.class);

    /**
     * Environment variables the CDK Provider framework sets on {@code framework.onEvent} only when an
     * {@code isComplete} handler is configured — i.e. when the two-phase async waiter is in play. Their
     * presence distinguishes a Provider-framework onEvent (which PUTs asynchronously via the waiter)
     * from an onEvent-only provider or a plain single-Lambda handler (which PUT synchronously).
     */
    private static final String CR_USER_IS_COMPLETE_ENV = "USER_IS_COMPLETE_FUNCTION_ARN";
    private static final String CR_WAITER_STATE_MACHINE_ENV = "WAITER_STATE_MACHINE_ARN";

    /**
     * How long to wait when the ServiceToken is a CDK Provider-framework {@code framework.onEvent}
     * Lambda: it does not PUT itself but returns after starting the waiter state machine, so the
     * ResponseURL callback arrives asynchronously once {@code framework.isComplete} reports done (or
     * {@code framework.onTimeout} reports failure).
     *
     * <p>This is an <em>idle</em> budget, not a total one: every waiter poll resets it (see {@link
     * CustomResourceResponseStore#touch}). Total time here is a property of the work rather than of
     * the emulator — {@code Custom::CreateOrganizationAccounts} creates one account per poll, so 15
     * accounts take five times as long as three — and a total budget would need re-tuning for every
     * config set while guillotining Lambdas that were succeeding. Measuring idleness still fails a
     * genuinely hung resource cleanly instead of hanging. Non-final so tests can shorten it.
     */
    private Duration asyncCustomResourceTimeout = Duration.ofMinutes(3);

    private final LambdaService lambdaService;

    @Inject
    ProviderFrameworkDetector(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    /**
     * The idle budget to allow the handler behind {@code serviceToken}.
     *
     * <p>{@code framework.onEvent} returns without PUTting (it starts the waiter state machine, which
     * drives {@code framework.isComplete} on a Retry cadence until it PUTs). So the callback lands
     * asynchronously and needs a longer wait than the synchronous single-Lambda pattern. onEvent-only
     * providers and plain handlers PUT during the invoke, so their await returns immediately
     * regardless of this budget.
     *
     * @param synchronousTimeout the budget for a handler that PUTs during its own invocation
     */
    Duration responseTimeout(String serviceToken, String region, Duration synchronousTimeout) {
        return isProviderFrameworkOnEvent(serviceToken, region)
                ? asyncCustomResourceTimeout : synchronousTimeout;
    }

    /**
     * True when the ServiceToken resolves to a CDK Provider-framework {@code framework.onEvent} Lambda,
     * i.e. one whose environment carries both {@code USER_IS_COMPLETE_FUNCTION_ARN} and
     * {@code WAITER_STATE_MACHINE_ARN}. The framework sets these only when a two-phase async waiter is
     * configured, so their presence means the ResponseURL callback will arrive asynchronously via the
     * waiter rather than synchronously during the invoke. Best-effort: an unresolvable function (or one
     * with no environment) is treated as a synchronous handler.
     */
    boolean isProviderFrameworkOnEvent(String serviceToken, String region) {
        try {
            LambdaFunction fn = lambdaService.getFunction(region, serviceToken);
            Map<String, String> env = fn != null ? fn.getEnvironment() : null;
            if (env == null) {
                return false;
            }
            String isComplete = env.get(CR_USER_IS_COMPLETE_ENV);
            String waiter = env.get(CR_WAITER_STATE_MACHINE_ENV);
            return isComplete != null && !isComplete.isBlank()
                    && waiter != null && !waiter.isBlank();
        } catch (RuntimeException e) {
            LOG.debugv("Could not read environment for custom-resource handler {0}: {1}",
                    serviceToken, e.getMessage());
            return false;
        }
    }

    /** Test hook: shortens the async-callback wait so the timeout bound can be exercised quickly. */
    void setAsyncCustomResourceTimeoutForTesting(Duration timeout) {
        this.asyncCustomResourceTimeout = timeout;
    }
}
