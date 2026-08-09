package io.github.hectorvent.floci.services.resourceexplorer2.query;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Known filter attributes in Resource Explorer query syntax.
 */
@RegisterForReflection
public enum FilterAttribute {
    ACCOUNT_ID("accountid"),
    APPLICATION("application"),
    ID("id"),
    REGION("region"),
    RESOURCE_TYPE("resourcetype"),
    RESOURCE_TYPE_SUPPORTS("resourcetype.supports"),
    SERVICE("service"),
    TAG("tag"),
    TAG_KEY("tag.key"),
    TAG_VALUE("tag.value");

    private static final Map<String, FilterAttribute> BY_PREFIX =
            Stream.of(values()).collect(Collectors.toMap(
                    a -> a.prefix.toLowerCase(Locale.ROOT), a -> a));

    private final String prefix;

    FilterAttribute(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public static FilterAttribute fromPrefix(String prefix) {
        return BY_PREFIX.get(prefix.toLowerCase(Locale.ROOT));
    }
}
