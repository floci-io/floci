package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;

import java.io.IOException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

final class CodeBuildPagination {

    private static final ObjectMapper TOKEN_MAPPER = new ObjectMapper();

    private CodeBuildPagination() {
    }

    static <T> Page<T> page(List<T> sorted, Comparator<T> comparator, int pageSize, String nextToken,
                            Function<String, T> readCursor, Function<T, String> writeCursor) {
        int start = 0;
        if (nextToken != null) {
            T anchor = readCursor.apply(nextToken);
            while (start < sorted.size() && comparator.compare(sorted.get(start), anchor) <= 0) {
                start++;
            }
        }
        int end = Math.min(start + pageSize, sorted.size());
        List<T> items = sorted.subList(start, end);
        String continuation = end < sorted.size() ? writeCursor.apply(items.getLast()) : null;
        return new Page<>(items, continuation);
    }

    static JsonNode readToken(String token) {
        if (token.isEmpty()) {
            throw new AwsException("InvalidInputException", "Invalid nextToken", 400);
        }
        try {
            return TOKEN_MAPPER.readTree(Base64.getUrlDecoder().decode(token));
        } catch (IllegalArgumentException | IOException e) {
            throw new AwsException("InvalidInputException", "Invalid nextToken", 400);
        }
    }

    static ObjectNode newToken() {
        return TOKEN_MAPPER.createObjectNode();
    }

    static String writeToken(ObjectNode token) {
        return Base64Url.encode(token.toString());
    }

    record Page<T>(List<T> items, String nextToken) {
    }
}
