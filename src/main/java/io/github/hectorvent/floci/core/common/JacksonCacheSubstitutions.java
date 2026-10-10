package io.github.hectorvent.floci.core.common;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Native image substitutions that give the shared Jackson caches in the image heap a new, unlocked
 * lock. The catalogs parsed while the image is built, such as AwsManagedPolicies, write to caches
 * every mapper shares: the type cache of TypeFactory.defaultInstance(), the annotation cache of the
 * default introspector, the mapper behind JsonNode.toString() and the field name InternCache. Adding
 * an entry to the first three, or a field name once InternCache is full, tries to take the cache's lock
 * for a moment. A heap scan inside that moment reaches the builder thread through the lock's owner and
 * fails the build. Giving each parse its own TypeFactory leaves the other shared caches, so the lock is
 * replaced in the two classes that own one.
 */
final class JacksonCacheSubstitutions {

    private JacksonCacheSubstitutions() {
    }
}

@TargetClass(className = "com.fasterxml.jackson.databind.util.internal.PrivateMaxEntriesMap")
final class Target_PrivateMaxEntriesMap {

    @Alias
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.NewInstance, declClass = ReentrantLock.class)
    Lock evictionLock;
}

@TargetClass(className = "com.fasterxml.jackson.core.util.InternCache")
final class Target_InternCache {

    @Alias
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.NewInstance, declClass = ReentrantLock.class)
    ReentrantLock lock;
}
