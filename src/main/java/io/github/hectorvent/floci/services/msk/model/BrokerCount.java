package io.github.hectorvent.floci.services.msk.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Result of parsing {@code numberOfBrokerNodes}: either an exact whole-number value, or a
 * marker that the request supplied something else.
 *
 * <p>{@link BrokerCountDeserializer} can't simply throw on malformed input: an exception
 * raised from inside a Jackson deserializer is caught while the request body is being read,
 * before the JAX-RS resource method - and therefore this service's own AWS-shaped
 * {@code AwsException} handling - ever runs, so the caller gets a bodyless 400 instead of an
 * MSK-style error. Carrying "malformed" as a value instead lets normal request validation
 * (see {@code MskService#validateCreateRequest}) raise the proper error once binding has
 * finished successfully.
 */
@RegisterForReflection
public final class BrokerCount {

    private static final BrokerCount MALFORMED = new BrokerCount(null, true);

    private final Integer value;
    private final boolean malformed;

    private BrokerCount(Integer value, boolean malformed) {
        this.value = value;
        this.malformed = malformed;
    }

    public static BrokerCount of(Integer value) {
        return new BrokerCount(value, false);
    }

    public static BrokerCount malformed() {
        return MALFORMED;
    }

    public Integer value() {
        return value;
    }

    public boolean isMalformed() {
        return malformed;
    }
}
