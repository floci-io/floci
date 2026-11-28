package io.floci.aws.testing;

/**
 * Names for {@code @ResourceLock}, for plain test classes that share process-wide state. ci.yml runs the
 * classes that start no Quarkus application four at a time; a class holding one of these locks runs
 * while no other holder of the same lock does.
 */
public final class ParallelLocks {

    /**
     * {@code TlsConfigSource}'s resolved TLS directory, a static field, and the certificate, key and
     * metadata files under it, which every test of the TLS configuration writes and reads back.
     */
    public static final String TLS_CONFIG = "floci-tls-config";

    /**
     * The host's TCP and UDP ports. A class that chooses a port, releases it and binds or dials it
     * later ({@code FreePorts}, a port expected to refuse) takes it in the default READ_WRITE mode; any
     * other class that opens a socket takes it in READ mode, so it cannot be handed that port by the
     * kernel in the gap. A class that starts a child process takes it in READ mode too: until the child
     * execs it holds a copy of every socket the JVM has open, so a listener closed meanwhile keeps its
     * port bound. {@code ParallelLocksInventoryTest} checks every plain class that opens a socket or
     * starts a process declares one or the other.
     */
    public static final String PORTS = "floci-local-ports";

    /**
     * SmallRye's generation of the {@code EmulatorConfig} mapping classes, which it defines the first time a
     * {@code SmallRyeConfigBuilder} builds that mapping. Two classes building it at once fail with
     * {@code NoClassDefFoundError} for a nested group's generated class. {@code ParallelLocksInventoryTest}
     * checks every plain class that builds it declares this lock.
     */
    public static final String CONFIG_MAPPING = "floci-config-mapping";

    /** The process-wide count of active Floci Docker callbacks. Producers read-lock; exact observers write-lock. */
    public static final String DOCKER_ACTIVE_STREAMS = "floci-docker-active-streams";

    /**
     * Static COPY/UNLOAD size and heap ceilings on {@code S3CopySimulator}
     * ({@code COPY_TRANSFORM_MAX_OBJECT_BYTES}, {@code COPY_TRANSFORM_HEAP_MIB},
     * {@code UNLOAD_TARGET_FILE_BYTES}, {@code UNLOAD_MAX_TOTAL_BYTES}, {@code UNLOAD_HEAP_MIB}),
     * which plain unit tests shrink or drain temporarily.
     */
    public static final String REDSHIFT_COPY_LIMITS = "floci-redshift-copy-limits";

    private ParallelLocks() {
    }
}
