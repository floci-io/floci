package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.DsqlPrincipals;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.iam.ScpProvider;
import io.floci.aws.testing.MutableClock;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class DsqlEndpointTest {

    private final MutableClock clock = new MutableClock();
    private DsqlEndpoint endpoint;

    @BeforeEach
    void createEndpoint() {
        @SuppressWarnings("unchecked")
        Instance<ScpProvider> scpProvider = mock(Instance.class);
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> resourcePolicyProviders = mock(Instance.class);
        endpoint = new DsqlEndpoint(mock(DsqlTlsCertificates.class), mock(IamService.class),
                mock(IamPolicyEvaluator.class), scpProvider, resourcePolicyProviders, mock(DsqlPrincipals.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), clock);
    }

    @AfterEach
    void stopEndpoint() {
        endpoint.stop();
    }

    /**
     * Ending a session waits for its client to take what it is sent, so the check of a session whose
     * client stopped reading can block; another session's check due with it still runs on time.
     */
    @Test
    void aCheckThatBlocksHoldsBackNoOtherCheck() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherRan = new CountDownLatch(1);
        try {
            endpoint.schedule(clock.instant(), () -> {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Stopping the endpoint interrupts the check, which then ends as a blocked write would.
                    Thread.currentThread().interrupt();
                }
            });
            endpoint.schedule(clock.instant(), otherRan::countDown);

            assertTrue(otherRan.await(10, TimeUnit.SECONDS), "the second check must not wait for the first");
        } finally {
            release.countDown();
        }
    }
}
