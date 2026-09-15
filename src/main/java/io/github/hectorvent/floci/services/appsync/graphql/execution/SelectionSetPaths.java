package io.github.hectorvent.floci.services.appsync.graphql.execution;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@code $ctx.info.selectionSetList} the way AppSync does: one entry per selected field in
 * document order, aliased fields listed under their alias only, nested fields as
 * {@code parent/child} paths.
 */
public final class SelectionSetPaths {

    private SelectionSetPaths() {}

    public static List<String> of(DataFetchingFieldSelectionSet selectionSet) {
        List<String> paths = new ArrayList<>();
        if (selectionSet != null) {
            append(selectionSet, "", paths);
        }
        return paths;
    }

    private static void append(DataFetchingFieldSelectionSet selectionSet, String prefix, List<String> paths) {
        for (SelectedField field : selectionSet.getImmediateFields()) {
            String path = prefix + field.getResultKey();
            if (paths.contains(path)) {
                continue;
            }
            paths.add(path);
            append(field.getSelectionSet(), path + "/", paths);
        }
    }
}
