package io.github.hectorvent.floci.services.route53;

import java.util.List;
import java.util.function.Function;

final class Route53Pagination {

    private Route53Pagination() {
    }

    static <T> Page<T> page(List<T> sortedItems, String marker, int pageSize, Function<T, String> id) {
        int start = 0;
        if (marker != null && !marker.isEmpty()) {
            for (int i = 0; i < sortedItems.size(); i++) {
                if (id.apply(sortedItems.get(i)).equals(marker)) {
                    start = i;
                    break;
                }
            }
        }

        int remaining = sortedItems.size() - start;
        if (pageSize <= 0 || remaining <= pageSize) {
            return new Page<>(sortedItems.subList(start, sortedItems.size()), null, null);
        }
        int end = start + pageSize;
        T nextItem = sortedItems.get(end);
        return new Page<>(sortedItems.subList(start, end), id.apply(nextItem), nextItem);
    }

    record Page<T>(List<T> items, String nextMarker, T nextItem) {
        boolean isTruncated() {
            return nextMarker != null;
        }
    }
}
