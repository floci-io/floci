package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The two lists ModifyDBInstance carries in {@code CloudwatchLogsExportConfiguration}.
 *
 * <p>They are deltas against whatever the instance already exports, not a replacement for it, which
 * is what separates a modify from a create. CreateDBInstance names the whole set once in
 * {@code EnableCloudwatchLogsExports}; a modify turns individual types on and off and leaves the
 * rest alone. A null list is one the request omitted.
 */
@RegisterForReflection
public record LogExportChanges(List<String> enableLogTypes, List<String> disableLogTypes) {

    /** The stored set with the enables added and the disables removed, order preserved. */
    public List<String> applyTo(List<String> current) {
        Set<String> result = new LinkedHashSet<>(current == null ? List.of() : current);
        if (enableLogTypes != null) {
            result.addAll(enableLogTypes);
        }
        if (disableLogTypes != null) {
            disableLogTypes.forEach(result::remove);
        }
        return new ArrayList<>(result);
    }
}
