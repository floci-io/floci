package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.CsvParser;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

class S3SelectEvaluator {

    private static final Logger LOG = Logger.getLogger(S3SelectEvaluator.class);

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
            if (pos >= tokens.size()) return new CompNode(col, "=", "");
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
                    default -> new CompNode(col, "=", "");
                };
            }
            if (next.type() == TokenType.OP) {
                String op = next.value(); advance();
                return new CompNode(col, op, consumeOperand());
            }
            return new CompNode(col, "=", "");
        }

        private String consumeOperand() {
            if (pos >= tokens.size()) return "";
            return tokens.get(pos++).value();
        }

        private boolean peekKeyword(String kw) {
            return pos < tokens.size()
                    && tokens.get(pos).type() == TokenType.KEYWORD
                    && kw.equals(tokens.get(pos).value());
        }

        private void advance() { if (pos < tokens.size()) pos++; }
    }

    private static WhereNode parseWhere(String where) {
        return new Parser(tokenize(where)).parse();
    }

    // ── CSV evaluation ─────────────────────────────────────────────────────

    static String evaluateCsv(String content, String expression, String fileHeaderInfo, String outputFormat) {
        Matcher matcher = SELECT_PATTERN.matcher(expression.trim());
        if (!matcher.find()) {
            LOG.debugv("SQL pattern did not match: {0}", expression);
            return content;
        }
        String projection = matcher.group(1).trim();
        String alias = matcher.group(2);
        String whereClause = matcher.group(3);
        String limitStr = matcher.group(4);

        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0) return "";

        List<String> headerList = new ArrayList<>();
        int dataStart = 0;
        if ("USE".equalsIgnoreCase(fileHeaderInfo)) {
            headerList = CsvParser.parseLine(lines[0]);
            dataStart = 1;
        } else if ("IGNORE".equalsIgnoreCase(fileHeaderInfo)) {
            dataStart = 1;
        }

        List<String[]> rows = new ArrayList<>();
        for (int i = dataStart; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            rows.add(CsvParser.parseLine(lines[i]).toArray(String[]::new));
        }

        if (whereClause != null) {
            rows = filterCsvRows(rows, headerList, alias, whereClause);
        }

        if (limitStr != null) {
            int limit = Integer.parseInt(limitStr);
            if (rows.size() > limit) rows = rows.subList(0, limit);
        }

        return projectCsvRows(rows, headerList, projection, outputFormat);
    }

    private static List<String[]> filterCsvRows(List<String[]> rows, List<String> headers,
                                                  String alias, String where) {
        String processed = where;
        if (alias != null) {
            processed = processed.replaceAll("(?i)" + Pattern.quote(alias) + "\\.", "");
        }
        try {
            WhereNode tree = parseWhere(processed);
            return rows.stream().filter(row -> evalCsv(tree, row, headers)).toList();
        } catch (Exception e) {
            LOG.warnv("WHERE parse error, returning all rows: {0}", e.getMessage());
            return rows;
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

    private static String projectCsvRows(List<String[]> rows, List<String> headers,
                                          String projection, String outputFormat) {
        boolean toJson = "JSON".equalsIgnoreCase(outputFormat);
        if ("*".equals(projection)) {
            return rows.stream()
                    .map(row -> toJson ? rowToJson(row, headers) : String.join(",", row))
                    .collect(Collectors.joining("\n")) + (rows.isEmpty() ? "" : "\n");
        }
        String[] cols = Arrays.stream(projection.split(",")).map(String::trim).toArray(String[]::new);
        return rows.stream().map(row -> {
            List<String> vals = Arrays.stream(cols)
                    .map(col -> { String v = getCellValue(row, headers, col); return v != null ? v : ""; })
                    .toList();
            if (toJson) {
                StringBuilder json = new StringBuilder("{");
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) json.append(",");
                    json.append('"').append(jsonEscape(cols[i])).append("\":\"")
                            .append(jsonEscape(vals.get(i))).append('"');
                }
                return json.append("}").toString();
            }
            return String.join(",", vals);
        }).collect(Collectors.joining("\n")) + (rows.isEmpty() ? "" : "\n");
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

    static String evaluateJson(String content, String expression, ObjectMapper mapper, String outputFormat) {
        Matcher matcher = SELECT_PATTERN.matcher(expression.trim());
        if (!matcher.find()) {
            LOG.debugv("SQL pattern did not match for JSON: {0}", expression);
            return content;
        }
        String projection = matcher.group(1).trim();
        String alias = matcher.group(2);
        String whereClause = matcher.group(3);
        String limitStr = matcher.group(4);

        WhereNode tree = null;
        if (whereClause != null) {
            String processed = whereClause;
            if (alias != null) {
                processed = processed.replaceAll("(?i)" + Pattern.quote(alias) + "\\.", "");
            }
            try {
                tree = parseWhere(processed);
            } catch (Exception e) {
                LOG.warnv("JSON WHERE parse error: {0}", e.getMessage());
            }
        }

        List<JsonNode> rows = parseJsonInput(content, mapper);

        if (tree != null) {
            final WhereNode finalTree = tree;
            rows = rows.stream().filter(row -> evalJson(finalTree, row)).toList();
        }

        if (limitStr != null) {
            int limit = Integer.parseInt(limitStr);
            if (rows.size() > limit) rows = rows.subList(0, limit);
        }

        return projectJsonRows(rows, projection, outputFormat, mapper);
    }

    private static List<JsonNode> parseJsonInput(String content, ObjectMapper mapper) {
        // Try JSON array first (starts with '['), then JSON Lines, then single object.
        if (content.trim().startsWith("[")) {
            try {
                JsonNode root = mapper.readTree(content);
                if (root.isArray()) {
                    return StreamSupport.stream(root.spliterator(), false).toList();
                }
            } catch (Exception ignored) {}
        }
        // JSON Lines: parse each non-empty line independently.
        List<JsonNode> rows = new ArrayList<>();
        for (String line : content.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            try { rows.add(mapper.readTree(line)); } catch (Exception ignored) {}
        }
        return rows;
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

    private static String projectJsonRows(List<JsonNode> rows, String projection,
                                           String outputFormat, ObjectMapper mapper) {
        boolean toCsv = "CSV".equalsIgnoreCase(outputFormat);
        String[] cols = "*".equals(projection) ? null
                : Arrays.stream(projection.split(",")).map(String::trim).toArray(String[]::new);
        StringBuilder sb = new StringBuilder();
        for (JsonNode row : rows) {
            if (cols == null) {
                if (toCsv) {
                    List<String> vals = new ArrayList<>();
                    row.fields().forEachRemaining(e -> vals.add(csvEscape(e.getValue().asText(""))));
                    sb.append(String.join(",", vals)).append("\n");
                } else {
                    try { sb.append(mapper.writeValueAsString(row)).append("\n"); } catch (Exception ignored) {}
                }
            } else {
                if (toCsv) {
                    sb.append(Arrays.stream(cols)
                            .map(col -> csvEscape(row.path(col).asText("")))
                            .collect(Collectors.joining(","))).append("\n");
                } else {
                    ObjectNode projected = mapper.createObjectNode();
                    for (String col : cols) {
                        JsonNode v = row.path(col);
                        if (!v.isMissingNode()) projected.set(col, v);
                    }
                    try { sb.append(mapper.writeValueAsString(projected)).append("\n"); } catch (Exception ignored) {}
                }
            }
        }
        return sb.toString();
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
