package io.github.hectorvent.floci.services.secretsmanager;

import java.util.function.Supplier;

/** Supplies the backend credential change for a service-owned secret rotation. */
public interface ManagedSecretRotator {

    boolean supports(String secretArn, String region);

    default void reserve(String secretArn, String region) { }

    default void release(String secretArn, String region) { }

    void rotate(String secretArn, String region, Rotation rotation);

    /** Commits a backend password change and its corresponding secret value as one rotation. */
    interface Rotation {
        void commit(Supplier<String> changeBackend, Runnable rollbackBackend);
    }
}
