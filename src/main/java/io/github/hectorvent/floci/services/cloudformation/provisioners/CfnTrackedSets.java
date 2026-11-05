package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Sets of identifiers persisted in a resource's internal tracking attributes. */
public final class CfnTrackedSets {

    private CfnTrackedSets() {
    }

    /** Returns a mutable set so a provisioner can update the recorded identifiers. */
    public static Set<String> read(StackResource resource, String attribute) {
        String raw = resource.getAttributes().get(attribute);
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(List.of(raw.split("\n")));
    }

    /**
     * Newline separated because an IAM policy name may contain a comma, per the
     * {@code [\w+=,.@-]+} pattern CloudFormation documents for {@code PolicyName}.
     */
    public static void write(StackResource resource, String attribute, Collection<String> values) {
        if (values.isEmpty()) {
            resource.getAttributes().remove(attribute);
            return;
        }
        resource.getAttributes().put(attribute, String.join("\n", values));
    }
}
