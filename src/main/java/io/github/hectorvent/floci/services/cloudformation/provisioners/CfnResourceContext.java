package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Read-only, operation-scoped access to resources managed by other stacks in the same account. */
public record CfnResourceContext(Supplier<List<StackResource>> otherManagedResources) {

    public static final CfnResourceContext EMPTY = new CfnResourceContext(List::of);

    /** The supplied resources are detached snapshots; the lookup is not atomic with service writes. */
    public boolean managedElsewhere(Predicate<StackResource> matches) {
        return otherManagedResources.get().stream().anyMatch(matches);
    }
}
