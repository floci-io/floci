package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.rds.proxy.PasswordValidator;
import io.github.hectorvent.floci.services.redshift.RedshiftCredentialBroker;
import io.github.hectorvent.floci.services.redshift.TempCredential;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessRuntimeTest {

    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String BACKEND_ID = "serverless_us-east-1_my-wg";
    private static final String RELAY_KEY = ACCOUNT + ":" + BACKEND_ID;
    private static final Endpoint ENDPOINT = new Endpoint("localhost", 7100);

    private final RedshiftContainerManager containers = mock(RedshiftContainerManager.class);
    private final RedshiftProxyManager proxies = mock(RedshiftProxyManager.class);
    private final RedshiftCredentialBroker broker = new RedshiftCredentialBroker();
    private final RedshiftServerlessRuntime runtime = new RedshiftServerlessRuntime(containers, proxies, broker);

    private static RedshiftContainerHandle handle() {
        return new RedshiftContainerHandle("container-id", BACKEND_ID, "172.17.0.5", 5432);
    }

    private PasswordValidator startAndCaptureValidator(RedshiftServerlessRuntime.AdminCredentials admin) {
        when(containers.start(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics")).thenReturn(handle());
        runtime.start(ACCOUNT, REGION, "my-wg", "root", "Secret123", "analytics", ENDPOINT, false,
                () -> Optional.ofNullable(admin));
        ArgumentCaptor<PasswordValidator> validator = ArgumentCaptor.forClass(PasswordValidator.class);
        verify(proxies).startProxy(eq(RELAY_KEY), eq(7100), eq("172.17.0.5"), eq(5432), eq("localhost"),
                eq("root"), eq("Secret123"), eq("analytics"), validator.capture(), eq(List.of()));
        return validator.getValue();
    }

    @Test
    void startRunsTheContainerThenFrontsItWithTheProxyOnTheAllocatedEndpoint() {
        when(containers.start(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics")).thenReturn(handle());

        RedshiftServerlessRuntime.Backend backend = runtime.start(ACCOUNT, REGION, "my-wg", "root", "Secret123",
                "analytics", ENDPOINT, false, Optional::empty);

        assertEquals(new RedshiftServerlessRuntime.Backend("172.17.0.5", 5432), backend);
        InOrder order = inOrder(containers, proxies);
        order.verify(containers).start(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics");
        order.verify(proxies).startProxy(eq(RELAY_KEY), eq(7100), eq("172.17.0.5"), eq(5432), eq("localhost"),
                eq("root"), eq("Secret123"), eq("analytics"), any(PasswordValidator.class), eq(List.of()));
        verify(containers, never()).adoptOrStart(any(), any(), any(), any(), any());
    }

    @Test
    void adoptReattachesToASurvivingContainer() {
        when(containers.adoptOrStart(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics")).thenReturn(handle());

        runtime.start(ACCOUNT, REGION, "my-wg", "root", "Secret123", "analytics", ENDPOINT, true, Optional::empty);

        verify(containers, never()).start(any(), any(), any(), any(), any());
        verify(proxies).startProxy(eq(RELAY_KEY), eq(7100), any(), anyInt(), any(), any(), any(), any(),
                any(PasswordValidator.class), any());
    }

    @Test
    void aProxyThatFailsToStartRollsBackTheContainerAndCredentials() {
        when(containers.start(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics")).thenReturn(handle());
        doThrow(new RuntimeException("port in use")).when(proxies).startProxy(any(), anyInt(), any(), anyInt(),
                any(), any(), any(), any(), any(PasswordValidator.class), any());
        broker.issue(ACCOUNT, BACKEND_ID, "IAM:alice", List.of(), 900);

        assertThrows(RuntimeException.class, () -> runtime.start(ACCOUNT, REGION, "my-wg", "root", "Secret123",
                "analytics", ENDPOINT, false, Optional::empty));

        verify(proxies).stopProxy(RELAY_KEY);
        verify(containers).stop(ACCOUNT, BACKEND_ID);
        assertTrue(broker.resolve(ACCOUNT, BACKEND_ID, "IAM:alice").isEmpty());
    }

    @Test
    void stopClosesTheProxyBeforeRemovingTheContainerAndRevokesCredentials() {
        broker.issue(ACCOUNT, BACKEND_ID, "IAM:alice", List.of(), 900);

        runtime.stop(ACCOUNT, REGION, "my-wg");

        InOrder order = inOrder(proxies, containers);
        order.verify(proxies).stopProxy(RELAY_KEY);
        order.verify(containers).stop(ACCOUNT, BACKEND_ID);
        assertTrue(broker.resolve(ACCOUNT, BACKEND_ID, "IAM:alice").isEmpty());
    }

    @Test
    void aProxyThatCannotBeClosedStopsTheStopBeforeTheContainerIsTouched() {
        doThrow(new RuntimeException("listener still bound")).when(proxies).stopProxy(RELAY_KEY);

        assertThrows(RuntimeException.class, () -> runtime.stop(ACCOUNT, REGION, "my-wg"));

        verify(containers, never()).stop(any(), any());
    }

    @Test
    void changeMasterPasswordUpdatesTheRoleAndTheProxyBackendLeg() {
        runtime.changeMasterPassword(ACCOUNT, REGION, "my-wg", "root", "analytics", "Secret123", "Changed123");

        verify(containers).alterUserPassword(ACCOUNT, BACKEND_ID, "root", "Changed123", "analytics");
        verify(proxies).updateMasterPassword(RELAY_KEY, "Changed123");
    }

    @Test
    void aProxyUpdateThatFailsPutsTheRolePasswordBack() {
        doThrow(new RuntimeException("proxy gone")).when(proxies).updateMasterPassword(RELAY_KEY, "Changed123");

        assertThrows(RuntimeException.class, () -> runtime.changeMasterPassword(
                ACCOUNT, REGION, "my-wg", "root", "analytics", "Secret123", "Changed123"));

        verify(containers).alterUserPassword(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics");
    }

    @Test
    void aFailedRollbackKeepsTheOriginalProxyFailureAsTheThrownError() {
        RuntimeException proxyFailure = new RuntimeException("proxy gone");
        doThrow(proxyFailure).when(proxies).updateMasterPassword(RELAY_KEY, "Changed123");
        doThrow(new RuntimeException("psql failed")).when(containers)
                .alterUserPassword(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> runtime.changeMasterPassword(
                ACCOUNT, REGION, "my-wg", "root", "analytics", "Secret123", "Changed123"));

        assertEquals(proxyFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
    }

    @Test
    void restoreMasterPasswordLeavesTheProxyAloneWhenTheRoleCannotBeRestored() {
        doThrow(new RuntimeException("psql failed")).when(containers)
                .alterUserPassword(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics");

        assertThrows(RuntimeException.class,
                () -> runtime.restoreMasterPassword(ACCOUNT, REGION, "my-wg", "root", "analytics", "Secret123"));

        verify(proxies, never()).updateMasterPassword(any(), any());
    }

    @Test
    void restoreMasterPasswordResetsTheRoleAndTheProxy() {
        runtime.restoreMasterPassword(ACCOUNT, REGION, "my-wg", "root", "analytics", "Secret123");

        verify(containers).alterUserPassword(ACCOUNT, BACKEND_ID, "root", "Secret123", "analytics");
        verify(proxies).updateMasterPassword(RELAY_KEY, "Secret123");
    }

    @Test
    void theAdminPairIsMasterEquivalentAndAWrongAdminPasswordIsRejected() {
        PasswordValidator validator = startAndCaptureValidator(
                new RedshiftServerlessRuntime.AdminCredentials("root", "Secret123"));

        assertEquals(PasswordValidator.AuthResult.MASTER_EQUIVALENT, validator.validate("root", "Secret123"));
        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("root", "wrong"));
    }

    @Test
    void aLiveGetCredentialsPasswordIsMasterEquivalentAndAStaleOneForThatUserIsRejected() {
        PasswordValidator validator = startAndCaptureValidator(
                new RedshiftServerlessRuntime.AdminCredentials("root", "Secret123"));

        TempCredential credential = runtime.issueCredential(ACCOUNT, REGION, "my-wg", "IAM:alice", 900);

        assertEquals(PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                validator.validate("IAM:alice", credential.password()));
        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("IAM:alice", "stale"));
        assertEquals(PasswordValidator.AuthResult.PASSTHROUGH, validator.validate("someone-else", "pw"));
    }

    @Test
    void aNamespaceThatIsGoneVouchesForNothing() {
        PasswordValidator validator = startAndCaptureValidator(null);
        TempCredential credential = runtime.issueCredential(ACCOUNT, REGION, "my-wg", "IAM:alice", 900);

        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("root", "Secret123"));
        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("IAM:alice", credential.password()));
    }

    @Test
    void theBackendIdCannotCollideWithAClusterIdentifier() {
        assertEquals(BACKEND_ID, RedshiftServerlessRuntime.backendId(REGION, "my-wg"));
        assertTrue(BACKEND_ID.contains("_"), "cluster identifiers cannot contain an underscore");
    }

    @Test
    void generatedPasswordsAreLongAlphanumericAndDifferEachTime() {
        String first = runtime.generatePassword();
        String second = runtime.generatePassword();

        assertEquals(32, first.length());
        assertTrue(first.matches("[A-Za-z0-9]+"), first);
        assertNotEquals(first, second);
    }
}
