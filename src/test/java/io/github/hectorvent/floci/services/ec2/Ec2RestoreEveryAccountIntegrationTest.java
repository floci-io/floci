package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Startup restore runs from {@code @PostConstruct}, on a thread with no request, where account-aware
 * storage resolves to the default account. These tests run it the same way, on a bare thread, for
 * an instance owned by another account.
 */
@QuarkusTest
@TestProfile(Ec2RestoreEveryAccountIntegrationTest.EnforcedContainerBackedEc2.class)
class Ec2RestoreEveryAccountIntegrationTest {

    /** Container-backed EC2 with security groups enforced, so restore resolves the instance's groups. */
    public static class EnforcedContainerBackedEc2 implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "true");
        }
    }

    private static final String REGION = "us-east-1";
    private static final String OTHER_ACCOUNT = "111122223333";

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    @InjectMock
    Ec2PortForwardManager portForwardManager;

    @InjectMock
    AmiImageResolver amiImageResolver;

    private final AtomicReference<Consumer<Instance>> persister = new AtomicReference<>();

    @BeforeEach
    void stubCollaborators() {
        when(amiImageResolver.resolveImage(any())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
        when(containerManager.restoreMetadataRegistration(any())).thenReturn(true);
        doAnswer(invocation -> {
            persister.set(invocation.getArgument(0));
            return null;
        }).when(portForwardManager).setPersister(any());
    }

    @Test
    void restoreKeepsAnotherAccountsInstanceRunningWhenItsSecurityGroupsAreEnforced() throws Throwable {
        Instance instance = runningInstanceOf(OTHER_ACCOUNT);

        restoreOutsideAnyRequest();

        verify(containerManager).restoreMetadataRegistration(argThat(restored -> isSame(restored, instance)));
        verify(containerManager, never()).stopForShutdown(argThat(stopped -> isSame(stopped, instance)));
        assertEquals("running", RequestScopes.callAs(OTHER_ACCOUNT,
                () -> describe(instance.getInstanceId()).getFirst().getState().getName()));
    }

    @Test
    void portForwardWritesDuringRestoreStayInTheInstancesOwnAccount() throws Throwable {
        Instance instance = runningInstanceOf(OTHER_ACCOUNT);
        doAnswer(invocation -> {
            Instance restored = invocation.getArgument(0);
            if (isSame(restored, instance)) {
                restored.getPublishedPorts().put(8080, 30080);
                persister.get().accept(restored);
            }
            return null;
        }).when(portForwardManager).restore(any());

        restoreOutsideAnyRequest();

        verify(portForwardManager).restore(argThat(restored -> isSame(restored, instance)));
        assertEquals(List.of(instance.getInstanceId()), RequestScopes.callAs(OTHER_ACCOUNT,
                () -> describe(instance.getInstanceId()).stream().map(Instance::getInstanceId).toList()));
        assertFalse(RequestScopes.callAs("000000000000", () -> describe(null).stream()
                .anyMatch(listed -> listed.getInstanceId().equals(instance.getInstanceId()))));
    }

    @Test
    void shutdownWritesAnotherAccountsInstanceOnlyToItsOwnAccount() throws Throwable {
        Instance instance = runningInstanceOf(OTHER_ACCOUNT);
        restoreOutsideAnyRequest();
        // Stopping a container unpublishes its forwards, and the port-forward manager persists that.
        doAnswer(invocation -> {
            Instance stopped = invocation.getArgument(0);
            if (isSame(stopped, instance)) {
                stopped.getPublishedPorts().clear();
                persister.get().accept(stopped);
            }
            return null;
        }).when(containerManager).stopForShutdown(any());

        runOutsideAnyRequest(service::stopManagedContainers);

        verify(containerManager).stopForShutdown(argThat(stopped -> isSame(stopped, instance)));
        assertEquals("stopped", RequestScopes.callAs(OTHER_ACCOUNT,
                () -> describe(instance.getInstanceId()).getFirst().getState().getName()));
        assertFalse(RequestScopes.callAs("000000000000", () -> describe(null).stream()
                .anyMatch(listed -> listed.getInstanceId().equals(instance.getInstanceId()))));
    }

    @Test
    void portForwardWritesFromAThreadWithNoRequestStayInTheInstancesOwnAccount() throws Throwable {
        // Ec2ContainerManager stops containers on its own executor, and unpublishing a forward there
        // persists the instance with no request, and so no account, in scope.
        Instance instance = runningInstanceOf(OTHER_ACCOUNT);
        restoreOutsideAnyRequest();

        assertNotNull(persister.get(), "the service registered no port-forward persister");
        runOutsideAnyRequest(() -> persister.get().accept(instance));

        assertEquals(List.of(instance.getInstanceId()), RequestScopes.callAs(OTHER_ACCOUNT,
                () -> describe(instance.getInstanceId()).stream().map(Instance::getInstanceId).toList()));
        assertFalse(RequestScopes.callAs("000000000000", () -> describe(null).stream()
                .anyMatch(listed -> listed.getInstanceId().equals(instance.getInstanceId()))));
    }

    private Instance runningInstanceOf(String accountId) {
        return RequestScopes.callAs(accountId, () -> {
            Instance launched = service.runInstances(REGION, "ami-amazonlinux2023", "t3.micro", 1, 1,
                    null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
            launched.setDockerContainerId("container-" + launched.getInstanceId());
            launched.setState(InstanceState.running());
            return launched;
        });
    }

    private void restoreOutsideAnyRequest() throws Throwable {
        runOutsideAnyRequest(service::restoreMetadataRegistrations);
    }

    /**
     * Runs work on a fresh thread, so no request (and no account) is in scope, and rethrows what
     * it threw: an exception on another thread would otherwise end only that thread.
     */
    private static void runOutsideAnyRequest(Runnable work) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private List<Instance> describe(String instanceId) {
        return service.describeInstances(REGION, instanceId == null ? List.of() : List.of(instanceId), Map.of())
                .stream().map(Reservation::getInstances).flatMap(List::stream).toList();
    }

    private static boolean isSame(Instance candidate, Instance instance) {
        return candidate != null && instance.getInstanceId().equals(candidate.getInstanceId());
    }
}
