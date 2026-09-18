package io.github.hectorvent.floci.services.appsync.graphql.execution;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filters the {@code data} of {@code $util.error} / {@code $util.appendError} to the fields the
 * query selected, as AppSync does. Values that are not maps (or lists of maps) pass through.
 */
public final class SelectionSetFilter {

    private SelectionSetFilter() {}

    public static Object filter(Object data, DataFetchingFieldSelectionSet selectionSet) {
        if (data == null || selectionSet == null) {
            return data;
        }
        List<SelectedField> fields = selectionSet.getImmediateFields();
        if (fields.isEmpty()) {
            return data;
        }
        return filterValue(data, fields);
    }

    private static Object filterValue(Object value, List<SelectedField> fields) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (SelectedField field : fields) {
                String name = field.getName();
                if (!map.containsKey(name) || out.containsKey(name)) {
                    continue;
                }
                Object child = map.get(name);
                List<SelectedField> sub = field.getSelectionSet().getImmediateFields();
                out.put(name, sub.isEmpty() ? child : filterValue(child, sub));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(filterValue(item, fields));
            }
            return out;
        }
        return value;
    }
}
