package io.floci.aws.services.apigateway;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ApiGatewayPagingWalk {

    private ApiGatewayPagingWalk() {
    }

    /**
     * Follows {@code position} from the first page to the last, one item per page, and returns the ids
     * seen in order. Fails if an id or a position repeats.
     */
    static <P, I> List<String> oneItemPerPageIds(Function<String, P> fetchPage, Function<P, List<I>> itemsOf,
                                                 Function<P, String> positionOf, Function<I, String> idOf) {
        List<String> seen = new ArrayList<>();
        List<String> positions = new ArrayList<>();
        String position = null;
        do {
            P page = fetchPage.apply(position);
            List<I> items = itemsOf.apply(page);
            assertEquals(1, items.size());
            String id = idOf.apply(items.get(0));
            assertFalse(seen.contains(id), "item repeated: " + id);
            seen.add(id);
            position = positionOf.apply(page);
            if (position != null) {
                assertFalse(positions.contains(position), "position repeated: " + position);
                positions.add(position);
            }
        } while (position != null);
        return seen;
    }
}
