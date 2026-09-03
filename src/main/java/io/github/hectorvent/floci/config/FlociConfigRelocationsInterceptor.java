package io.github.hectorvent.floci.config;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

import java.io.Serial;
import java.util.*;
import java.util.Map.Entry;

import static java.util.Map.entry;
import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * Keeps configuration keys working after they have been moved to a new place in the
 * {@code floci} config tree, typically when a flat {@code floci.*} property is pulled down
 * into a sub-mapping ({@code floci.protocols.*}, {@code floci.services.lambda.*}, …).
 *
 * <p>A hand-rolled equivalent of SmallRye's {@code RelocateConfigSourceInterceptor}: whenever
 * the <em>new</em> key is requested, the <em>legacy</em> key is consulted too, and the legacy
 * value only wins when the new key was left untouched (i.e. its value is just the
 * {@code @WithDefault}) or the legacy value comes from a higher-priority source.
 *
 * <p>{@link #iterateNames(ConfigSourceInterceptorContext)} rewrites any legacy key it sees to
 * the new name, so config-mapping validation and {@code getPropertyNames()} never treat the
 * legacy keys as unknown properties.
 *
 * <p><strong>Adding a relocation:</strong> append a {@code legacy -> current} entry to
 * {@link #RELOCATIONS}. Everything else (both lookup directions, name iteration) is derived
 * from that single list. Each key (legacy or current) must appear at most once.
 *
 * <p>Registered via {@code META-INF/services/io.smallrye.config.ConfigSourceInterceptor}. The
 * priority places it inside the expression interceptor so an expression such as
 * <code>${floci.protocols.max-request-size}</code> in {@code application.yml} also picks up a
 * value set under the legacy key.
 */
@Priority(Priorities.LIBRARY + 200 - 5)
public class FlociConfigRelocationsInterceptor implements ConfigSourceInterceptor {

    @Serial
    private static final long serialVersionUID = 2446657606783245344L;

    /**
     * The relocations, as {@code legacy key -> current key}. This is the only place a
     * relocation is declared; the lookup maps below are built from it.
     */
    static final List<Entry<String, String>> RELOCATIONS = List.of(
            entry("floci.max-request-size", "floci.protocols.max-request-size"),
            entry("floci.ecr-base-uri", "floci.services.lambda.ecr-base-uri")
    );

    /**
     * current key &rarr; legacy key, consulted while resolving a value.
     */
    private static final Map<String, String> CURRENT_TO_LEGACY = RELOCATIONS.stream()
            .collect(toUnmodifiableMap(Entry::getValue, Entry::getKey));

    /**
     * legacy key &rarr; current key, applied while iterating property names.
     */
    private static final Map<String, String> LEGACY_TO_CURRENT = RELOCATIONS.stream()
            .collect(toUnmodifiableMap(Entry::getKey, Entry::getValue));

    @Override
    public ConfigValue getValue(final ConfigSourceInterceptorContext context, final String name) {
        final ConfigValue value = context.proceed(name);

        final String legacyName = CURRENT_TO_LEGACY.get(name);
        if (legacyName == null) {
            return value;
        }

        final ConfigValue legacyValue = context.proceed(legacyName);
        if (legacyValue == null || legacyValue.getValue() == null) {
            return value;
        }

        if (value == null
                || value.getValue() == null
                || legacyValue.getConfigSourceOrdinal() > value.getConfigSourceOrdinal()) {
            return legacyValue.withName(name);
        }
        return value;
    }

    @Override
    public Iterator<String> iterateNames(final ConfigSourceInterceptorContext context) {
        final Set<String> names = new HashSet<>();
        final Iterator<String> iterator = context.iterateNames();
        while (iterator.hasNext()) {
            final String name = iterator.next();
            names.add(LEGACY_TO_CURRENT.getOrDefault(name, name));
        }
        return names.iterator();
    }
}
