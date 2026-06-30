package io.github.hectorvent.floci.services.appsync.graphql.util;

import java.util.*;
import java.util.stream.Collectors;

public class ListUtil {

    public List<Object> copyAndRetainAll(List<?> list, List<?> keep) {
        if (list == null || keep == null) {
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        }
        Set<Object> keepSet = new HashSet<>(keep);
        return list.stream()
                .filter(keepSet::contains)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<Object> copyAndRemoveAll(List<?> list, List<?> remove) {
        if (list == null) {
            return new ArrayList<>();
        }
        if (remove == null) {
            return new ArrayList<>(list);
        }
        Set<Object> removeSet = new HashSet<>(remove);
        return list.stream()
                .filter(item -> !removeSet.contains(item))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @SuppressWarnings("unchecked")
    public List<Object> sortList(List<?> list, boolean descending, String fieldName) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        List<Object> sorted = new ArrayList<>(list);
        if (fieldName == null || fieldName.isEmpty()) {
            sorted.sort((a, b) -> compareObjects(a, b, descending));
        } else {
            sorted.sort((a, b) -> {
                Object valA = getFieldValue(a, fieldName);
                Object valB = getFieldValue(b, fieldName);
                return compareObjects(valA, valB, descending);
            });
        }
        return sorted;
    }

    private int compareObjects(Object a, Object b, boolean descending) {
        int result;
        if (a == null && b == null) {
            result = 0;
        } else if (a == null) {
            result = -1;
        } else if (b == null) {
            result = 1;
        } else if (a instanceof Comparable<?> && b.getClass().isAssignableFrom(a.getClass())) {
            result = ((Comparable<Object>) a).compareTo(b);
        } else {
            result = a.toString().compareTo(b.toString());
        }
        return descending ? -result : result;
    }

    private Object getFieldValue(Object obj, String fieldName) {
        if (obj instanceof Map<?, ?> map) {
            return map.get(fieldName);
        }
        return null;
    }
}
