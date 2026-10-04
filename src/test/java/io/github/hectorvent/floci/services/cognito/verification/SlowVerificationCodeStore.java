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
 * A verification-code store for concurrency tests. Every read hands back its own copy a moment late, as a
 * read from disk does, so requests redeeming one code at once all find it unused; and every delete lands a
 * moment late, after a write another request made meanwhile, so redeeming a code can delete the one a resend
 * just wrote. Neither happens unless {@link VerificationCodeService} lets one request at a time at a code.
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
        pause();
        return read;
    }

    @Override
    public void delete(String key) {
        pause();
        super.delete(key);
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on the verification-code store", e);
        }
    }
}
