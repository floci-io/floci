package io.github.hectorvent.floci.services.appsync.graphql.util;

import java.util.*;
import java.util.stream.Collectors;

public class MapUtil {

    public Map<String, Object> copyAndRetainAllKeys(Map<String, Object> map, List<?> keys) {
        if (map == null || keys == null) {
            return map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>();
        }
        Set<Object> keySet = new HashSet<>(keys);
        return map.entrySet().stream()
                .filter(entry -> keySet.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    public Map<String, Object> copyAndRemoveAllKeys(Map<String, Object> map, List<?> keys) {
        if (map == null) {
            return new LinkedHashMap<>();
        }
        if (keys == null) {
            return new LinkedHashMap<>(map);
        }
        Set<Object> keySet = new HashSet<>(keys);
        return map.entrySet().stream()
                .filter(entry -> !keySet.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }
}
