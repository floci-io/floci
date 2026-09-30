package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A verification-code store for concurrency tests. Every read comes a moment late and hands back its own
 * copy, as a read from disk does, so requests redeeming one code at once all find it unused unless
 * {@link VerificationCodeService#consume} lets only one of them read it at a time.
 */
public final class SlowVerificationCodeStore extends InMemoryStorage<String, VerificationCode> {

    private SlowVerificationCodeStore() {
    }

    /** A storage factory that gives a {@link VerificationCodeService} a store of its own like this one. */
    public static StorageFactory factory() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                new AccountAwareStorageBackend<>(new SlowVerificationCodeStore(), null, "000000000000"));
        return factory;
    }

    @Override
    public Optional<VerificationCode> get(String key) {
        Optional<VerificationCode> read = super.get(key).map(code -> new VerificationCode(code.getUserPoolId(),
                code.getUsername(), code.getPurpose(), code.getCodeHash(), code.getSalt(), code.getIssuedAt(),
                code.getExpiresAt(), code.getAttemptsRemaining(), code.isConsumed()));
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading a verification code", e);
        }
        return read;
    }
}
