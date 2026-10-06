package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.Totp;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.VirtualMfaDevice;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A virtual MFA device holds a shared secret, so it must not be reachable from another account.
 * These drive one {@link IamService} whose request account can be switched, which is what a
 * caller presenting credentials for a different account amounts to.
 */
class VirtualMfaAccountScopeTest {

    private static final String ACCOUNT_A = "000000000000";
    private static final String ACCOUNT_B = "111111111111";

    @SuppressWarnings("unchecked")
    private static Instance<RequestContext> requestContextFor(AtomicReference<String> accountId) {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenAnswer(invocation -> accountId.get());
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    @Test
    void aDeviceIsInvisibleAndUnusableFromAnotherAccount() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_A);
        AccountAwareStorageBackend<VirtualMfaDevice> devices = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextFor(account), ACCOUNT_A);
        IamService service = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), devices,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", ACCOUNT_A), false, null);

        VirtualMfaDevice device = service.createVirtualMfaDevice("shared-name", "/", Map.of());
        String serial = device.getSerialNumber();
        assertTrue(serial.contains(ACCOUNT_A), "the serial number carries the owning account");

        // Now the same service, serving a request that belongs to another account.
        account.set(ACCOUNT_B);

        assertTrue(service.listVirtualMfaDevices("Any").isEmpty(),
                "account B must not see account A's devices");
        AwsException read = assertThrows(AwsException.class, () -> service.getVirtualMfaDevice(serial));
        assertEquals("NoSuchEntity", read.getErrorCode());
        AwsException delete = assertThrows(AwsException.class,
                () -> service.deleteVirtualMfaDevice(serial));
        assertEquals("NoSuchEntity", delete.getErrorCode());
        // And the seed cannot be reached indirectly through the tag reader either.
        assertThrows(AwsException.class, () -> service.listMfaDeviceTags(serial));

        // Account A still has it, so nothing was destroyed by the cross-account attempts.
        account.set(ACCOUNT_A);
        assertEquals(serial, service.getVirtualMfaDevice(serial).getSerialNumber());
        assertEquals(1, service.listVirtualMfaDevices("Any").size());
    }

    /** The same device name in two accounts is two devices, each with its own secret. */
    @Test
    void theSameDeviceNameInTwoAccountsGetsDistinctSeeds() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_A);
        AccountAwareStorageBackend<VirtualMfaDevice> devices = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextFor(account), ACCOUNT_A);
        IamService service = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), devices,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", ACCOUNT_A), false, null);

        VirtualMfaDevice inA = service.createVirtualMfaDevice("same-name", "/", Map.of());
        account.set(ACCOUNT_B);
        VirtualMfaDevice inB = service.createVirtualMfaDevice("same-name", "/", Map.of());

        assertTrue(!inA.getBase32Seed().equals(inB.getBase32Seed()),
                "a device in another account must not share the secret");
        // Codes minted from account A's seed must not open account B's device.
        long step = Totp.stepAt(Instant.now());
        assertTrue(!VirtualMfaCodes.matchesConsecutiveCodes(inB.getBase32Seed(),
                Totp.codeAt(inA.getBase32Seed(), step - 1),
                Totp.codeAt(inA.getBase32Seed(), step), 1, Instant.now()));
    }
}
