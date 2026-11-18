package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Where each statement of a policy document sits in the document's own text, which is what a
 * simulation reports for the statement that decided it.
 *
 * <p>A result's {@code Statement} carries "the row and column of the beginning of the
 * {@code Statement} in an IAM policy" and the row and column of its end. AWS's own published
 * example says exactly which character those are. Its document opens the statement on its third
 * line, where the brace that opens it sits in column 15, and the response reports a start position
 * of line 3, column 16; it closes the statement with a brace in column 3 of the seventh line, and
 * the response reports line 7, column 4. So both positions name the character just past the
 * statement's braces, which is where a streaming parse stands once it has read the opening brace
 * and once it has read past the statement.
 *
 * <p>The positions are collected in the order the statements are parsed in, one per element of a
 * {@code Statement} array whatever that element is and one for a {@code Statement} object, so an
 * index into the parsed statements is an index into these. A document that cannot be read this far
 * carries no positions, which is what a document that fails to parse contributes to a decision
 * anyway.
 */
final class PolicyStatementPositions {

    private static final JsonFactory FACTORY = new JsonFactory();

    /**
     * One statement's place in its document, as the two published positions: the row and column
     * just past its opening brace, and the row and column just past its close.
     */
    record Span(int startLine, int startColumn, int endLine, int endColumn) {
    }

    private PolicyStatementPositions() {
    }

    /** Every statement's span, in the order the document lists them, or empty for one unreadable this far. */
    static List<Span> of(String document) {
        if (document == null || document.isBlank()) {
            return List.of();
        }
        try (JsonParser parser = FACTORY.createParser(document)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return List.of();
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String element = parser.currentName();
                JsonToken value = parser.nextToken();
                if (!"Statement".equals(element)) {
                    parser.skipChildren();
                    continue;
                }
                return statementSpans(parser, value);
            }
            return List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Span> statementSpans(JsonParser parser, JsonToken value) throws IOException {
        if (value == JsonToken.START_OBJECT) {
            return List.of(span(parser));
        }
        if (value != JsonToken.START_ARRAY) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            spans.add(span(parser));
        }
        return List.copyOf(spans);
    }

    /**
     * The span of the value the parser has just entered. The location is read where the parse
     * stands, which is past the token just read: past the opening brace before the children are
     * skipped, and past the statement's last token after.
     */
    private static Span span(JsonParser parser) throws IOException {
        JsonLocation start = parser.currentLocation();
        parser.skipChildren();
        JsonLocation end = parser.currentLocation();
        return new Span(start.getLineNr(), start.getColumnNr(), end.getLineNr(), end.getColumnNr());
    }
}
