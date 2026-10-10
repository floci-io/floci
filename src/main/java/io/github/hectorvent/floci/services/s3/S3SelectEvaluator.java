package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CsvParser;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class S3SelectEvaluator {

    private static final Logger LOG = Logger.getLogger(S3SelectEvaluator.class);

    // S3 Select's limit on the length of a record in the input or the result, its maxCharsPerRecord of 1 MB.
    static final int MAX_RECORD_CHARS = 1024 * 1024;
    // A JSON parser reads ahead of the element it is on; past the record limit by this much, the
    // element is too long whatever the read-ahead holds.
    private static final int PARSER_READ_AHEAD = 64 * 1024;

    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "SELECT\\s+(.+?)\\s+FROM\\s+S3OBJECT(?:\\s+(\\w+))?\\s*(?:WHERE\\s+(.+?))?\\s*(?:LIMIT\\s+(\\d+))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // ── AST node types ─────────────────────────────────────────────────────

    private sealed interface WhereNode
            permits AndNode, OrNode, NotNode, CompNode, IsNullNode, BetweenNode, InNode, LikeNode {}

    private record AndNode(WhereNode left, WhereNode right) implements WhereNode {}
    private record OrNode(WhereNode left, WhereNode right) implements WhereNode {}
    private record NotNode(WhereNode child) implements WhereNode {}
    private record CompNode(String col, String op, String val) implements WhereNode {}
    private record IsNullNode(String col, boolean negate) implements WhereNode {}
    private record BetweenNode(String col, String lo, String hi) implements WhereNode {}
    private record InNode(String col, List<String> vals) implements WhereNode {}
    private record LikeNode(String col, String pattern) implements WhereNode {}

    // ── Lexer ──────────────────────────────────────────────────────────────

    private enum TokenType { IDENT, STRING, NUMBER, OP, KEYWORD, LPAREN, RPAREN, COMMA }

    private record Token(TokenType type, String value) {}

    private static final Set<String> KEYWORDS = Set.of(
            "AND", "OR", "NOT", "LIKE", "BETWEEN", "IN", "IS", "NULL", "TRUE", "FALSE"
    );

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(")); i++; continue; }
            if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")")); i++; continue; }
            if (c == ',') { tokens.add(new Token(TokenType.COMMA, ",")); i++; continue; }

            if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < input.length()) {
                    char ch = input.charAt(i);
                    if (ch == '\'' && i + 1 < input.length() && input.charAt(i + 1) == '\'') {
                        sb.append('\''); i += 2;
                    } else if (ch == '\'') {
                        i++; break;
                    } else {
                        sb.append(ch); i++;
                    }
                }
                tokens.add(new Token(TokenType.STRING, sb.toString()));
                continue;
            }

            if (Character.isDigit(c) || (c == '-' && i + 1 < input.length() && Character.isDigit(input.charAt(i + 1)))) {
                int start = i++;
                while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) i++;
                tokens.add(new Token(TokenType.NUMBER, input.substring(start, i)));
                continue;
            }

            if (i + 1 < input.length()) {
                String two = input.substring(i, i + 2);
                if (">=".equals(two) || "<=".equals(two) || "<>".equals(two) || "!=".equals(two)) {
                    tokens.add(new Token(TokenType.OP, two)); i += 2; continue;
                }
            }
            if (c == '>' || c == '<' || c == '=') {
                tokens.add(new Token(TokenType.OP, String.valueOf(c))); i++; continue;
            }

            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < input.length() && (Character.isLetterOrDigit(input.charAt(i))
                        || input.charAt(i) == '_' || input.charAt(i) == '.')) i++;
                String word = input.substring(start, i);
                String upper = word.toUpperCase();
                if (KEYWORDS.contains(upper)) {
                    tokens.add(new Token(TokenType.KEYWORD, upper));
                } else {
                    tokens.add(new Token(TokenType.IDENT, word));
                }
                continue;
            }
            i++;
        }
        return tokens;
    }

    // ── Recursive descent parser ───────────────────────────────────────────

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) { this.tokens = tokens; }

        WhereNode parse() { return orExpr(); }

        private WhereNode orExpr() {
            WhereNode left = andExpr();
            while (peekKeyword("OR")) { advance(); left = new OrNode(left, andExpr()); }
            return left;
        }

        private WhereNode andExpr() {
            WhereNode left = notExpr();
            while (peekKeyword("AND")) { advance(); left = new AndNode(left, notExpr()); }
            return left;
        }

        private WhereNode notExpr() {
            if (peekKeyword("NOT")) { advance(); return new NotNode(notExpr()); }
            return atom();
        }

        private WhereNode atom() {
            if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LPAREN) {
                advance();
                WhereNode inner = orExpr();
                if (pos < tokens.size() && tokens.get(pos).type() == TokenType.RPAREN) advance();
                return inner;
            }
            String col = consumeOperand();
            if (pos >= tokens.size()) {
                throw unsupportedWhereExpression("Missing predicate operator after: " + col);
            }
            Token next = tokens.get(pos);
            if (next.type() == TokenType.KEYWORD) {
                return switch (next.value()) {
                    case "IS" -> {
                        advance();
                        boolean negate = peekKeyword("NOT");
                        if (negate) advance();
                        if (peekKeyword("NULL")) advance();
                        yield new IsNullNode(col, negate);
                    }
                    case "BETWEEN" -> {
                        advance();
                        String lo = consumeOperand();
                        if (peekKeyword("AND")) advance();
                        yield new BetweenNode(col, lo, consumeOperand());
                    }
                    case "IN" -> {
                        advance();
                        if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LPAREN) advance();
                        List<String> vals = new ArrayList<>();
                        while (pos < tokens.size() && tokens.get(pos).type() != TokenType.RPAREN) {
                            vals.add(consumeOperand());
                            if (pos < tokens.size() && tokens.get(pos).type() == TokenType.COMMA) advance();
                        }
                        if (pos < tokens.size() && tokens.get(pos).type() == TokenType.RPAREN) advance();
                        yield new InNode(col, vals);
                    }
                    case "LIKE" -> {
                        advance();
                        yield new LikeNode(col, consumeOperand());
                    }
                    default -> throw unsupportedWhereExpression("Unsupported predicate keyword: " + next.value());
                };
            }
            if (next.type() == TokenType.OP) {
                String op = next.value(); advance();
                return new CompNode(col, op, consumeOperand());
            }
            throw unsupportedWhereExpression("Unsupported predicate near: " + next.value());
        }

        private String consumeOperand() {
            if (pos >= tokens.size()) {
                throw unsupportedWhereExpression("Missing predicate operand");
            }
            Token token = tokens.get(pos++);
            if (token.type() == TokenType.LPAREN || token.type() == TokenType.RPAREN || token.type() == TokenType.COMMA) {
                throw unsupportedWhereExpression("Unsupported predicate operand: " + token.value());
            }
            return token.value();
        }

        private boolean peekKeyword(String kw) {
            return pos < tokens.size()
                    && tokens.get(pos).type() == TokenType.KEYWORD
                    && kw.equals(tokens.get(pos).value());
        }

        private void advance() { if (pos < tokens.size()) pos++; }
    }

    static AwsException overMaxRecordSize() {
        return new AwsException("OverMaxRecordSize",
                "The length of a record in the input or result is greater than the maxCharsPerRecord limit of 1 MB.", 400);
    }

    private static AwsException unsupportedWhereExpression(String expression) {
        return new AwsException(
                "ExternalEvalException",
                "Unsupported S3 Select WHERE expression: " + expression,
                400
        );
    }

    private static WhereNode parseWhere(String where) {
        return new Parser(tokenize(where)).parse();
    }

    // ── Query ──────────────────────────────────────────────────────────────

    /** A parsed {@code SELECT ... FROM S3Object} statement, checked before any of the object is read. */
    static final class Query {
        private final String[] columns;
        private final WhereNode where;
        private final Integer limit;

        private Query(String[] columns, WhereNode where, Integer limit) {
            this.columns = columns;
            this.where = where;
            this.limit = limit;
        }

        private boolean limitReached(int selected) {
            return limit != null && selected >= limit;
        }
    }

    /** Where an evaluation hands each selected record, without its line break. */
    @FunctionalInterface
    interface RecordSink {
        void accept(String record) throws IOException;
    }

    /**
     * Parses {@code expression}, or returns {@code null} for one this evaluator does not read, whose
     * object is then returned as it is. A WHERE clause it cannot evaluate fails here.
     */
    static Query parse(String expression) {
        Matcher matcher = SELECT_PATTERN.matcher(expression.trim());
        if (!matcher.find()) {
            LOG.debugv("SQL pattern did not match: {0}", expression);
            return null;
        }
        String projection = matcher.group(1).trim();
        String alias = matcher.group(2);
        String whereClause = matcher.group(3);
        String limitStr = matcher.group(4);

        WhereNode where = null;
        if (whereClause != null) {
            String processed = whereClause;
            if (alias != null) {
                processed = processed.replaceAll("(?i)" + Pattern.quote(alias) + "\\.", "");
            }
            try {
                where = parseWhere(processed);
            } catch (AwsException e) {
                throw e;
            } catch (Exception e) {
                LOG.warnv("WHERE parse error: {0}", e.getMessage());
                throw unsupportedWhereExpression(processed);
            }
        }
        String[] columns = "*".equals(projection) ? null
                : Arrays.stream(projection.split(",")).map(String::trim).toArray(String[]::new);
        return new Query(columns, where, limitStr != null ? Integer.parseInt(limitStr) : null);
    }

    // ── CSV evaluation ─────────────────────────────────────────────────────

    /**
     * Evaluates {@code query} over CSV read from {@code in} one line at a time, handing each selected
     * record to {@code out}, and stops reading once the LIMIT is reached.
     */
    static void evaluateCsv(BufferedReader reader, Query query, String fileHeaderInfo, String outputFormat,
                            RecordSink out) throws IOException {
        LineReader in = new LineReader(reader);
        List<String> headers = new ArrayList<>();
        if ("USE".equalsIgnoreCase(fileHeaderInfo) || "IGNORE".equalsIgnoreCase(fileHeaderInfo)) {
            String header = in.readLine();
            if (header == null) {
                return;
            }
            if ("USE".equalsIgnoreCase(fileHeaderInfo)) {
                headers = CsvParser.parseLine(header);
            }
        }
        boolean toJson = "JSON".equalsIgnoreCase(outputFormat);
        int selected = 0;
        String line;
        while (!query.limitReached(selected) && (line = in.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] row = CsvParser.parseLine(line).toArray(String[]::new);
            if (query.where != null && !evalCsv(query.where, row, headers)) {
                continue;
            }
            out.accept(projectCsvRow(row, headers, query.columns, toJson));
            selected++;
        }
    }

    private static boolean evalCsv(WhereNode node, String[] row, List<String> headers) {
        return switch (node) {
            case AndNode(var l, var r) -> evalCsv(l, row, headers) && evalCsv(r, row, headers);
            case OrNode(var l, var r) -> evalCsv(l, row, headers) || evalCsv(r, row, headers);
            case NotNode(var child) -> !evalCsv(child, row, headers);
            case IsNullNode(var col, var negate) -> {
                String v = getCellValue(row, headers, col);
                yield negate ? v != null : v == null;
            }
            case BetweenNode(var col, var lo, var hi) -> {
                String v = getCellValue(row, headers, col);
                yield v != null && cmp(v, lo) >= 0 && cmp(v, hi) <= 0;
            }
            case InNode(var col, var vals) -> {
                String v = getCellValue(row, headers, col);
                yield v != null && vals.stream().anyMatch(v::equals);
            }
            case LikeNode(var col, var pattern) -> {
                String v = getCellValue(row, headers, col);
                yield v != null && matchLike(v, pattern);
            }
            case CompNode(var col, var op, var val) -> {
                String v = getCellValue(row, headers, col);
                yield v != null && compareOp(v, op, val);
            }
        };
    }

    private static String projectCsvRow(String[] row, List<String> headers, String[] columns, boolean toJson) {
        if (columns == null) {
            return toJson ? rowToJson(row, headers) : String.join(",", row);
        }
        List<String> vals = Arrays.stream(columns)
                .map(col -> {
                    String v = getCellValue(row, headers, col);
                    return v != null ? v : "";
                })
                .toList();
        if (toJson) {
            StringBuilder json = new StringBuilder("{");
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    json.append(",");
                }
                json.append('"').append(jsonEscape(columns[i])).append("\":\"")
                        .append(jsonEscape(vals.get(i))).append('"');
            }
            return json.append("}").toString();
        }
        return String.join(",", vals);
    }

    // ── Duck rows formatting ───────────────────────────────────────────────

    /**
     * Serializes rows returned by floci-duck (/query) into the requested output format.
     * DuckDB already applied filtering and projection, so this is pure serialization.
     */
    static String formatDuckRows(List<Map<String, Object>> rows, String outputFormat, ObjectMapper mapper) {
        boolean toJson = "JSON".equalsIgnoreCase(outputFormat);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (toJson) {
                try {
                    sb.append(mapper.writeValueAsString(row)).append("\n");
                } catch (Exception ignored) {}
            } else {
                sb.append(row.values().stream()
                        .map(v -> v == null ? "" : csvEscape(v.toString()))
                        .collect(Collectors.joining(","))).append("\n");
            }
        }
        return sb.toString();
    }

    // ── JSON evaluation ────────────────────────────────────────────────────

    /**
     * Evaluates {@code query} over JSON read from {@code in}, a record at a time: the elements of a
     * top-level array, or else one JSON value per line. Each selected record goes to {@code out}, and
     * reading stops once the LIMIT is reached.
     */
    static void evaluateJson(BufferedReader in, Query query, ObjectMapper mapper, String outputFormat,
                             RecordSink out) throws IOException {
        boolean toCsv = "CSV".equalsIgnoreCase(outputFormat);
        int selected = 0;
        if (startsWithArray(in)) {
            ElementLimitedReader limited = new ElementLimitedReader(in);
            JsonParser parser = mapper.getFactory().createParser(limited);
            try {
                parser.nextToken();
                while (!query.limitReached(selected)) {
                    JsonToken token = parser.nextToken();
                    if (token == null || token == JsonToken.END_ARRAY) {
                        break;
                    }
                    limited.startElement();
                    long start = parser.currentTokenLocation().getCharOffset();
                    JsonNode row = mapper.readTree(parser);
                    limited.endElement();
                    if (parser.currentLocation().getCharOffset() - start > MAX_RECORD_CHARS) {
                        throw overMaxRecordSize();
                    }
                    if (query.where == null || evalJson(query.where, row)) {
                        out.accept(projectJsonRow(row, query.columns, toCsv, mapper));
                        selected++;
                    }
                }
            } catch (JsonProcessingException e) {
                // An element the parser gave up on past the record limit is over it, whatever stopped
                // the parser: a number, for one, it reads whole before checking its own length limit.
                if (limited.overRecordLimit()) {
                    throw overMaxRecordSize();
                }
                // The records before the malformed part have been returned; the rest cannot be read.
                LOG.debugv("Stopped reading a malformed JSON array for S3 Select: {0}", e.getOriginalMessage());
            }
            return;
        }
        LineReader lines = new LineReader(in);
        String line;
        while (!query.limitReached(selected) && (line = lines.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode row;
            try {
                row = mapper.readTree(line);
            } catch (JsonProcessingException ignored) {
                // A line that is not JSON is not a record; it is skipped, as it always has been.
                continue;
            }
            if (query.where == null || evalJson(query.where, row)) {
                out.accept(projectJsonRow(row, query.columns, toCsv, mapper));
                selected++;
            }
        }
    }

    /** Whether the first character after any leading whitespace opens an array; nothing is consumed. */
    private static boolean startsWithArray(BufferedReader in) throws IOException {
        while (true) {
            in.mark(1);
            int c = in.read();
            if (c < 0) {
                return false;
            }
            if (!Character.isWhitespace(c)) {
                in.reset();
                return c == '[';
            }
        }
    }

    private static boolean evalJson(WhereNode node, JsonNode row) {
        return switch (node) {
            case AndNode(var l, var r) -> evalJson(l, row) && evalJson(r, row);
            case OrNode(var l, var r) -> evalJson(l, row) || evalJson(r, row);
            case NotNode(var child) -> !evalJson(child, row);
            case IsNullNode(var col, var negate) -> {
                JsonNode v = row.path(col);
                boolean isNull = v.isMissingNode() || v.isNull();
                yield negate ? !isNull : isNull;
            }
            case BetweenNode(var col, var lo, var hi) -> {
                String v = row.path(col).asText(null);
                yield v != null && cmp(v, lo) >= 0 && cmp(v, hi) <= 0;
            }
            case InNode(var col, var vals) -> {
                String v = row.path(col).asText(null);
                yield v != null && vals.stream().anyMatch(v::equals);
            }
            case LikeNode(var col, var pattern) -> {
                String v = row.path(col).asText(null);
                yield v != null && matchLike(v, pattern);
            }
            case CompNode(var col, var op, var val) -> {
                String v = row.path(col).asText(null);
                yield v != null && compareOp(v, op, val);
            }
        };
    }

    private static String projectJsonRow(JsonNode row, String[] columns, boolean toCsv, ObjectMapper mapper)
            throws JsonProcessingException {
        if (columns == null) {
            if (toCsv) {
                List<String> vals = new ArrayList<>();
                row.fields().forEachRemaining(e -> vals.add(csvEscape(e.getValue().asText(""))));
                return String.join(",", vals);
            }
            return mapper.writeValueAsString(row);
        }
        if (toCsv) {
            return Arrays.stream(columns)
                    .map(col -> csvEscape(row.path(col).asText("")))
                    .collect(Collectors.joining(","));
        }
        ObjectNode projected = mapper.createObjectNode();
        for (String col : columns) {
            JsonNode v = row.path(col);
            if (!v.isMissingNode()) {
                projected.set(col, v);
            }
        }
        return mapper.writeValueAsString(projected);
    }

    // ── Bounded reading ────────────────────────────────────────────────────

    /**
     * Reads the lines of an object, split at {@code \n} with a {@code \r} before it dropped, the way
     * the object has always been split, and fails a line longer than the record limit before holding
     * more of it.
     */
    private static final class LineReader {

        private final Reader in;
        private final char[] buffer = new char[8192];
        private int position;
        private int limit;

        LineReader(Reader in) {
            this.in = in;
        }

        /** The next line, or {@code null} at the end of the object. */
        String readLine() throws IOException {
            StringBuilder line = null;
            while (true) {
                if (position == limit) {
                    int read = in.read(buffer, 0, buffer.length);
                    if (read < 0) {
                        if (line != null && line.length() > MAX_RECORD_CHARS) {
                            throw overMaxRecordSize();
                        }
                        return line == null ? null : line.toString();
                    }
                    position = 0;
                    limit = read;
                }
                int start = position;
                while (position < limit && buffer[position] != '\n') {
                    position++;
                }
                if (line == null) {
                    line = new StringBuilder();
                }
                line.append(buffer, start, position - start);
                // One character over the limit can still be the \r of a \r\n.
                if (line.length() > MAX_RECORD_CHARS + 1) {
                    throw overMaxRecordSize();
                }
                if (position < limit) {
                    position++;
                    int length = line.length();
                    if (length > 0 && line.charAt(length - 1) == '\r') {
                        line.setLength(length - 1);
                    }
                    if (line.length() > MAX_RECORD_CHARS) {
                        throw overMaxRecordSize();
                    }
                    return line.toString();
                }
            }
        }
    }

    /**
     * Counts what a JSON parser reads towards the array element it is on, so an element cannot grow
     * past the record limit, by more than the parser's read-ahead, before it is rejected. Everything
     * read inside an element counts. Between elements only what is not whitespace counts: whitespace
     * there is skipped without being held, while anything else is the next element, which a parser
     * can read whole before it is handed over, as it does a number.
     */
    private static final class ElementLimitedReader extends FilterReader {

        private boolean inElement;
        private long counted;

        ElementLimitedReader(Reader in) {
            super(in);
        }

        void startElement() {
            inElement = true;
        }

        void endElement() {
            inElement = false;
            counted = 0;
        }

        boolean overRecordLimit() {
            return counted > MAX_RECORD_CHARS;
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) {
                count((char) c);
            }
            return c;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            for (int i = offset; i < offset + read; i++) {
                count(buffer[i]);
            }
            return read;
        }

        private void count(char c) {
            if (inElement || !Character.isWhitespace(c)) {
                counted++;
                if (counted > MAX_RECORD_CHARS + PARSER_READ_AHEAD) {
                    throw overMaxRecordSize();
                }
            }
        }
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    private static boolean compareOp(String cell, String op, String val) {
        int c = cmp(cell, val);
        return switch (op) {
            case "="       -> c == 0;
            case "<>", "!=" -> c != 0;
            case ">"       -> c > 0;
            case "<"       -> c < 0;
            case ">="      -> c >= 0;
            case "<="      -> c <= 0;
            default -> false;
        };
    }

    private static int cmp(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static boolean matchLike(String value, String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') regex.append(".*");
            else if (c == '_') regex.append(".");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append("$").toString(), Pattern.DOTALL).matcher(value).matches();
    }

    private static String rowToJson(String[] row, List<String> headers) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) json.append(",");
            String val = i < row.length ? row[i] : "";
            json.append('"').append(jsonEscape(headers.get(i).trim())).append("\":\"")
                    .append(jsonEscape(val)).append('"');
        }
        return json.append("}").toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String csvEscape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String getCellValue(String[] row, List<String> headers, String colName) {
        if (colName.startsWith("_")) {
            try {
                int idx = Integer.parseInt(colName.substring(1)) - 1;
                return idx >= 0 && idx < row.length ? row[idx] : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).trim().equalsIgnoreCase(colName)) {
                return i < row.length ? row[i] : null;
            }
        }
        return null;
    }
}
