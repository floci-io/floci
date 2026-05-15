package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Proper tokenizer/parser/evaluator for DynamoDB filter expressions and key condition expressions.
 * Replaces the regex-based splitting approach that breaks on compact formats like
 * {@code (#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)}.
 */
final class ExpressionEvaluator {

    private ExpressionEvaluator() {}

    // ── Token types ──

    enum TokenType {
        // Literals / identifiers
        IDENTIFIER,      // plain name like "pk", "info"
        NAME_REF,        // #name
        VALUE_REF,       // :value
        // Keywords
        AND, OR, NOT, IN, BETWEEN,
        // Comparators
        EQ,    // =
        NE,    // <>
        LT,    // <
        LE,    // <=
        GT,    // >
        GE,    // >=
        // Punctuation
        LPAREN, RPAREN, COMMA, DOT,
        LIST_INDEX,      // [n] — list element access
        // Functions
        FUNCTION,        // attribute_exists, attribute_not_exists, begins_with, contains, size
        // End
        EOF
    }

    record Token(TokenType type, String value, int pos) {}

    // ── Tokenizer ──

    static List<Token> tokenize(String expression) {
        var tokens = new ArrayList<Token>();
        int i = 0;
        int len = expression.length();

        while (i < len) {
            char c = expression.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // Punctuation
            if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(", i)); i++; continue; }
            if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")", i)); i++; continue; }
            if (c == ',') { tokens.add(new Token(TokenType.COMMA, ",", i)); i++; continue; }
            if (c == '.') { tokens.add(new Token(TokenType.DOT, ".", i)); i++; continue; }

            // List index [n]
            if (c == '[') {
                int start = i;
                i++; // skip '['
                while (i < len && Character.isDigit(expression.charAt(i))) i++;
                if (i < len && expression.charAt(i) == ']') {
                    i++; // skip ']'
                    tokens.add(new Token(TokenType.LIST_INDEX, expression.substring(start, i), start));
                } else {
                    throw new IllegalArgumentException(
                            "Expected ']' after list index at position " + i + " in: " + expression);
                }
                continue;
            }

            // Comparators (must check <> and <= before <, >= before >)
            if (c == '<') {
                if (i + 1 < len && expression.charAt(i + 1) == '>') {
                    tokens.add(new Token(TokenType.NE, "<>", i)); i += 2;
                } else if (i + 1 < len && expression.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.LE, "<=", i)); i += 2;
                } else {
                    tokens.add(new Token(TokenType.LT, "<", i)); i++;
                }
                continue;
            }
            if (c == '>') {
                if (i + 1 < len && expression.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.GE, ">=", i)); i += 2;
                } else {
                    tokens.add(new Token(TokenType.GT, ">", i)); i++;
                }
                continue;
            }
            if (c == '=') { tokens.add(new Token(TokenType.EQ, "=", i)); i++; continue; }

            // Value reference :name
            if (c == ':') {
                int start = i;
                i++; // skip ':'
                while (i < len && isNameChar(expression.charAt(i))) i++;
                tokens.add(new Token(TokenType.VALUE_REF, expression.substring(start, i), start));
                continue;
            }

            // Name reference #name
            if (c == '#') {
                int start = i;
                i++; // skip '#'
                while (i < len && isNameChar(expression.charAt(i))) i++;
                tokens.add(new Token(TokenType.NAME_REF, expression.substring(start, i), start));
                continue;
            }

            // Identifier or keyword
            if (isNameStartChar(c)) {
                int start = i;
                while (i < len && isNameChar(expression.charAt(i))) i++;
                // Check for function names that contain underscores (attribute_exists, etc.)
                // and multi-word function names
                String word = expression.substring(start, i);

                // Handle "attribute_exists", "attribute_not_exists" — need to consume underscores and following parts
                // Actually the loop above already handles underscores via isNameChar.
                // Check for known functions
                String wordLower = word.toLowerCase();
                switch (wordLower) {
                    case "and" -> tokens.add(new Token(TokenType.AND, word, start));
                    case "or" -> tokens.add(new Token(TokenType.OR, word, start));
                    case "not" -> tokens.add(new Token(TokenType.NOT, word, start));
                    case "in" -> tokens.add(new Token(TokenType.IN, word, start));
                    case "between" -> tokens.add(new Token(TokenType.BETWEEN, word, start));
                    case "attribute_exists", "attribute_not_exists", "begins_with", "contains", "size",
                         "attribute_type" ->
                        tokens.add(new Token(TokenType.FUNCTION, word, start));
                    default -> tokens.add(new Token(TokenType.IDENTIFIER, word, start));
                }
                continue;
            }

            throw new IllegalArgumentException(
                    "Unexpected character '%c' at position %d in expression: %s".formatted(c, i, expression));
        }

        tokens.add(new Token(TokenType.EOF, "", len));
        return tokens;
    }

    private static boolean isNameStartChar(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ── AST nodes ──

    sealed interface Expr {}

    record AndExpr(List<Expr> operands) implements Expr {}
    record OrExpr(List<Expr> operands) implements Expr {}
    record NotExpr(Expr operand) implements Expr {}
    record CompareExpr(Operand left, TokenType op, Operand right) implements Expr {}
    record BetweenExpr(Operand value, Operand low, Operand high) implements Expr {}
    record InExpr(Operand value, List<Operand> candidates) implements Expr {}
    record FunctionCallExpr(String functionName, List<Operand> args) implements Expr {}

    sealed interface Operand {}
    record PathOperand(List<String> segments) implements Operand {}      // e.g. ["info", "#name"] or ["pk"]
    record PlaceholderOperand(String name) implements Operand {}         // e.g. ":val"
    record FunctionOperand(String functionName, List<Operand> args) implements Operand {} // e.g. size(path)

    // ── Parser ──

    static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
            this.pos = 0;
        }

        private Token peek() { return tokens.get(pos); }
        private Token advance() { return tokens.get(pos++); }

        private Token expect(TokenType type) {
            Token t = advance();
            if (t.type() != type) {
                throw new IllegalArgumentException(
                        "Expected %s but got %s ('%s') at position %d".formatted(type, t.type(), t.value(), t.pos()));
            }
            return t;
        }

        Expr parseExpression() {
            Expr expr = parseOrExpr();
            // Don't require EOF here — caller may stop before consuming all tokens (e.g. splitKeyCondition)
            return expr;
        }

        private Expr parseOrExpr() {
            var operands = new ArrayList<Expr>();
            operands.add(parseAndExpr());
            while (peek().type() == TokenType.OR) {
                advance(); // consume OR
                operands.add(parseAndExpr());
            }
            return operands.size() == 1 ? operands.getFirst() : new OrExpr(operands);
        }

        private Expr parseAndExpr() {
            var operands = new ArrayList<Expr>();
            operands.add(parseNotExpr());
            while (peek().type() == TokenType.AND) {
                advance(); // consume AND
                operands.add(parseNotExpr());
            }
            return operands.size() == 1 ? operands.getFirst() : new AndExpr(operands);
        }

        private Expr parseNotExpr() {
            if (peek().type() == TokenType.NOT) {
                advance(); // consume NOT
                return new NotExpr(parseNotExpr());
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            Token current = peek();

            // Parenthesized expression
            if (current.type() == TokenType.LPAREN) {
                advance(); // consume (
                Expr inner = parseOrExpr();
                expect(TokenType.RPAREN);
                return inner;
            }

            // Function call as condition (attribute_exists, attribute_not_exists, begins_with, contains, size)
            if (current.type() == TokenType.FUNCTION) {
                String funcName = advance().value();
                expect(TokenType.LPAREN);
                var args = parseOperandList();
                expect(TokenType.RPAREN);

                // If followed by a comparator, this is "size(path) = :val" — treat as comparison
                if (isComparator(peek().type())) {
                    TokenType op = advance().type();
                    Operand right = parseOperand();
                    return new CompareExpr(new FunctionOperand(funcName, args), op, right);
                }

                return new FunctionCallExpr(funcName, args);
            }

            // Operand followed by comparator, IN, or BETWEEN
            Operand left = parseOperand();

            Token next = peek();
            if (next.type() == TokenType.IN) {
                advance(); // consume IN
                expect(TokenType.LPAREN);
                var candidates = parseOperandList();
                expect(TokenType.RPAREN);
                return new InExpr(left, candidates);
            }
            if (next.type() == TokenType.BETWEEN) {
                advance(); // consume BETWEEN
                Operand low = parseOperand();
                expect(TokenType.AND); // BETWEEN ... AND ...
                Operand high = parseOperand();
                return new BetweenExpr(left, low, high);
            }
            if (isComparator(next.type())) {
                TokenType op = advance().type();
                Operand right = parseOperand();
                return new CompareExpr(left, op, right);
            }

            throw new IllegalArgumentException(
                    "Expected comparator, IN, or BETWEEN after operand, but got %s ('%s') at position %d"
                            .formatted(next.type(), next.value(), next.pos()));
        }

        private Operand parseOperand() {
            Token current = peek();

            // Function as operand (e.g. size(path))
            if (current.type() == TokenType.FUNCTION) {
                String funcName = advance().value();
                expect(TokenType.LPAREN);
                var args = parseOperandList();
                expect(TokenType.RPAREN);
                return new FunctionOperand(funcName, args);
            }

            // Placeholder :value
            if (current.type() == TokenType.VALUE_REF) {
                return new PlaceholderOperand(advance().value());
            }

            // Path: identifier or #name, possibly dotted with nested [n] list indices
            if (current.type() == TokenType.IDENTIFIER || current.type() == TokenType.NAME_REF) {
                var segments = new ArrayList<String>();
                segments.add(advance().value());
                // Consume any immediately-following list indices (no dot needed before [n])
                while (peek().type() == TokenType.LIST_INDEX) {
                    segments.add(advance().value());
                }
                // Consume .name and .name[n] sequences
                while (peek().type() == TokenType.DOT) {
                    advance(); // consume dot
                    Token seg = peek();
                    if (seg.type() == TokenType.IDENTIFIER || seg.type() == TokenType.NAME_REF) {
                        segments.add(advance().value());
                        while (peek().type() == TokenType.LIST_INDEX) {
                            segments.add(advance().value());
                        }
                    } else {
                        throw new IllegalArgumentException(
                                "Expected identifier after '.' at position %d".formatted(seg.pos()));
                    }
                }
                return new PathOperand(segments);
            }

            throw new IllegalArgumentException(
                    "Expected operand but got %s ('%s') at position %d"
                            .formatted(current.type(), current.value(), current.pos()));
        }

        private List<Operand> parseOperandList() {
            var list = new ArrayList<Operand>();
            list.add(parseOperand());
            while (peek().type() == TokenType.COMMA) {
                advance(); // consume comma
                list.add(parseOperand());
            }
            return list;
        }

        private static boolean isComparator(TokenType type) {
            return type == TokenType.EQ || type == TokenType.NE ||
                   type == TokenType.LT || type == TokenType.LE ||
                   type == TokenType.GT || type == TokenType.GE;
        }
    }

    // ── Parse helper ──

    static Expr parse(String expression) {
        if (expression == null || expression.isBlank()) return null;
        var tokens = tokenize(expression.trim());
        var parser = new Parser(tokens);
        Expr expr = parser.parseExpression();
        if (parser.peek().type() != TokenType.EOF) {
            throw new IllegalArgumentException(
                    "Unexpected token '%s' at position %d after parsing complete expression"
                            .formatted(parser.peek().value(), parser.peek().pos()));
        }
        return expr;
    }

    // ── Key condition splitting ──

    /**
     * Splits a key condition expression into [pkCondition, skCondition].
     * Finds the top-level AND that is NOT part of a BETWEEN...AND.
     * Returns a 2-element array; the second element is null if there is no SK condition.
     */
    static String[] splitKeyCondition(String expression) {
        if (expression == null || expression.isBlank()) return new String[]{expression, null};

        // Strip a single layer of outer parens when they wrap the entire expression,
        // e.g. "(pk = :pk AND sk = :sk)" → "pk = :pk AND sk = :sk"
        String stripped = expression.strip();
        if (stripped.startsWith("(") && stripped.endsWith(")")) {
            int depth = 0;
            boolean wrapsAll = true;
            for (int i = 0; i < stripped.length() - 1; i++) {
                if (stripped.charAt(i) == '(') depth++;
                else if (stripped.charAt(i) == ')') {
                    depth--;
                    if (depth == 0) { wrapsAll = false; break; }
                }
            }
            if (wrapsAll) {
                expression = stripped.substring(1, stripped.length() - 1).strip();
            }
        }

        var tokens = tokenize(expression.trim());
        // Find the top-level AND that separates PK from SK.
        // We need to skip AND tokens that are part of BETWEEN...AND.
        // Strategy: walk through tokens tracking parenthesis depth and BETWEEN state.

        int depth = 0;
        boolean inBetween = false;

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.type()) {
                case LPAREN -> depth++;
                case RPAREN -> depth--;
                case BETWEEN -> { if (depth == 0) inBetween = true; }
                case AND -> {
                    if (depth == 0) {
                        if (inBetween) {
                            // This AND belongs to BETWEEN...AND — skip it
                            inBetween = false;
                        } else {
                            // This is the top-level AND separating PK from SK
                            int splitCharPos = t.pos();
                            // Find end of AND keyword in source
                            String trimmed = expression.trim();
                            String pk = trimmed.substring(0, splitCharPos).trim();
                            // The AND keyword is at splitCharPos, length varies (could be "AND" = 3 chars)
                            // But we need to find the actual end in the original string.
                            // Token value is the literal text, and pos is position in original.
                            int andEnd = splitCharPos + t.value().length();
                            String sk = trimmed.substring(andEnd).trim();
                            return new String[]{pk, sk};
                        }
                    }
                }
                default -> {}
            }
        }

        // No top-level AND found — expression is PK-only
        return new String[]{expression.trim(), null};
    }

    // ── Evaluator ──

    private static final String DOT_ESCAPE = "\uFF0E";

    /**
     * Evaluates a parsed expression against a DynamoDB item.
     *
     * @param expr           the parsed expression (may be null for "no filter")
     * @param item           the DynamoDB item (JsonNode map of attribute names to typed values)
     * @param exprAttrNames  expression attribute names mapping (#name -> actual name), may be null
     * @param exprAttrValues expression attribute values mapping (:val -> DynamoDB typed value), may be null
     * @return true if the item matches the expression
     */
    static boolean evaluate(Expr expr, JsonNode item, JsonNode exprAttrNames, JsonNode exprAttrValues) {
        if (expr == null) return true;

        return switch (expr) {
            case AndExpr and -> {
                for (Expr op : and.operands()) {
                    if (!evaluate(op, item, exprAttrNames, exprAttrValues)) yield false;
                }
                yield true;
            }
            case OrExpr or -> {
                for (Expr op : or.operands()) {
                    if (evaluate(op, item, exprAttrNames, exprAttrValues)) yield true;
                }
                yield false;
            }
            case NotExpr not -> !evaluate(not.operand(), item, exprAttrNames, exprAttrValues);

            case CompareExpr cmp -> evaluateComparison(cmp, item, exprAttrNames, exprAttrValues);
            case BetweenExpr bet -> evaluateBetween(bet, item, exprAttrNames, exprAttrValues);
            case InExpr in -> evaluateIn(in, item, exprAttrNames, exprAttrValues);
            case FunctionCallExpr func -> evaluateFunction(func, item, exprAttrNames, exprAttrValues);
        };
    }

    /**
     * Convenience: parse and evaluate in one call.
     */
    static boolean matches(String expression, JsonNode item, JsonNode exprAttrNames, JsonNode exprAttrValues) {
        return evaluate(parse(expression), item, exprAttrNames, exprAttrValues);
    }

    // ── Comparison evaluation ──

    private static boolean evaluateComparison(CompareExpr cmp, JsonNode item,
                                               JsonNode exprAttrNames, JsonNode exprAttrValues) {
        // For EQ/NE, resolve full attribute values to support set comparison
        if (cmp.op() == TokenType.EQ || cmp.op() == TokenType.NE) {
            JsonNode leftNode = resolveAttributeValue(cmp.left(), item, exprAttrNames, exprAttrValues);
            JsonNode rightNode = resolveAttributeValue(cmp.right(), item, exprAttrNames, exprAttrValues);
            if (leftNode == null && cmp.op() == TokenType.NE) return true;
            if (rightNode == null && cmp.op() == TokenType.NE) return true;
            if (leftNode == null || rightNode == null) return false;
            boolean equal = attributeValuesEqual(leftNode, rightNode);
            return cmp.op() == TokenType.EQ ? equal : !equal;
        }

        JsonNode leftNode = resolveAttributeValue(cmp.left(), item, exprAttrNames, exprAttrValues);
        JsonNode rightNode = resolveAttributeValue(cmp.right(), item, exprAttrNames, exprAttrValues);
        if (leftNode == null || rightNode == null) return false;
        int cmpResult = compareAttributeValues(leftNode, rightNode);
        return switch (cmp.op()) {
            case LT -> cmpResult < 0;
            case LE -> cmpResult <= 0;
            case GT -> cmpResult > 0;
            case GE -> cmpResult >= 0;
            default -> false;
        };
    }

    private static boolean evaluateBetween(BetweenExpr bet, JsonNode item,
                                            JsonNode exprAttrNames, JsonNode exprAttrValues) {
        JsonNode val = resolveAttributeValue(bet.value(), item, exprAttrNames, exprAttrValues);
        JsonNode low = resolveAttributeValue(bet.low(), item, exprAttrNames, exprAttrValues);
        JsonNode high = resolveAttributeValue(bet.high(), item, exprAttrNames, exprAttrValues);
        if (val == null || low == null || high == null) return false;
        return compareAttributeValues(val, low) >= 0 && compareAttributeValues(val, high) <= 0;
    }

    private static boolean evaluateIn(InExpr in, JsonNode item,
                                       JsonNode exprAttrNames, JsonNode exprAttrValues) {
        // For IN, we use type-aware equality via the raw attribute value nodes
        JsonNode leftAttrValue = resolveAttributeValue(in.value(), item, exprAttrNames, exprAttrValues);
        if (leftAttrValue == null) return false;

        for (Operand candidate : in.candidates()) {
            JsonNode candidateValue = resolveAttributeValue(candidate, item, exprAttrNames, exprAttrValues);
            if (candidateValue != null && attributeValuesEqual(leftAttrValue, candidateValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateFunction(FunctionCallExpr func, JsonNode item,
                                             JsonNode exprAttrNames, JsonNode exprAttrValues) {
        String funcLower = func.functionName().toLowerCase();
        return switch (funcLower) {
            case "attribute_exists" -> {
                if (func.args().isEmpty()) yield false;
                String path = resolveAttributePath(func.args().getFirst(), exprAttrNames);
                yield item != null && resolveNestedAttribute(item, path) != null;
            }
            case "attribute_not_exists" -> {
                if (func.args().isEmpty()) yield false;
                String path = resolveAttributePath(func.args().getFirst(), exprAttrNames);
                yield item == null || resolveNestedAttribute(item, path) == null;
            }
            case "begins_with" -> {
                if (func.args().size() < 2) yield false;
                String path = resolveAttributePath(func.args().get(0), exprAttrNames);
                JsonNode attrNode = item != null ? resolveNestedAttribute(item, path) : null;
                JsonNode prefixNode = resolveAttributeValue(func.args().get(1), item, exprAttrNames, exprAttrValues);
                if (attrNode == null || prefixNode == null) yield false;
                if (attrNode.has("B") && prefixNode.has("B")) {
                    byte[] attrBytes = java.util.Base64.getDecoder().decode(attrNode.get("B").asText());
                    byte[] prefixBytes = java.util.Base64.getDecoder().decode(prefixNode.get("B").asText());
                    if (prefixBytes.length > attrBytes.length) yield false;
                    for (int bi = 0; bi < prefixBytes.length; bi++) {
                        if (attrBytes[bi] != prefixBytes[bi]) yield false;
                    }
                    yield true;
                }
                String actual = extractScalarValue(attrNode);
                String prefix = extractScalarValue(prefixNode);
                yield actual != null && prefix != null && actual.startsWith(prefix);
            }
            case "contains" -> {
                if (func.args().size() < 2) yield false;
                yield evaluateContains(func.args().get(0), func.args().get(1),
                        item, exprAttrNames, exprAttrValues);
            }
            case "attribute_type" -> {
                if (func.args().size() < 2) yield false;
                String attrPath = resolveAttributePath(func.args().get(0), exprAttrNames);
                JsonNode attrVal = item != null ? resolveNestedAttribute(item, attrPath) : null;
                JsonNode typeNode = resolveAttributeValue(func.args().get(1), item, exprAttrNames, exprAttrValues);
                if (attrVal == null || typeNode == null) yield false;
                String typeCode = typeNode.has("S") ? typeNode.get("S").asText() : typeNode.asText();
                yield attrVal.has(typeCode);
            }
            default -> false;
        };
    }

    private static boolean evaluateContains(Operand pathOperand, Operand searchOperand,
                                             JsonNode item, JsonNode exprAttrNames, JsonNode exprAttrValues) {
        if (item == null) return false;
        String path = resolveAttributePath(pathOperand, exprAttrNames);
        JsonNode attrNode = resolveNestedAttribute(item, path);
        if (attrNode == null) return false;

        JsonNode searchAttrValue = resolveAttributeValue(searchOperand, item, exprAttrNames, exprAttrValues);
        if (searchAttrValue == null) return false;

        // List membership
        if (attrNode.has("L")) {
            for (JsonNode element : attrNode.get("L")) {
                if (attributeValuesEqual(element, searchAttrValue)) return true;
            }
            return false;
        }
        // String set
        if (attrNode.has("SS")) {
            if (!searchAttrValue.has("S")) return false;
            String target = searchAttrValue.get("S").asText();
            for (JsonNode element : attrNode.get("SS")) {
                if (target.equals(element.asText())) return true;
            }
            return false;
        }
        // Number set
        if (attrNode.has("NS")) {
            if (!searchAttrValue.has("N")) return false;
            try {
                BigDecimal target = new BigDecimal(searchAttrValue.get("N").asText());
                for (JsonNode element : attrNode.get("NS")) {
                    if (target.compareTo(new BigDecimal(element.asText())) == 0) return true;
                }
            } catch (NumberFormatException ignored) {}
            return false;
        }
        // Binary set
        if (attrNode.has("BS")) {
            if (!searchAttrValue.has("B")) return false;
            String target = searchAttrValue.get("B").asText();
            for (JsonNode element : attrNode.get("BS")) {
                if (target.equals(element.asText())) return true;
            }
            return false;
        }
        // String contains (substring)
        if (attrNode.has("S") && searchAttrValue.has("S")) {
            return attrNode.get("S").asText().contains(searchAttrValue.get("S").asText());
        }
        return false;
    }

    // ── Operand resolution ──

    /**
     * Resolves an operand to a scalar string value (for comparisons and BETWEEN).
     */
    private static String resolveScalar(Operand operand, JsonNode item,
                                         JsonNode exprAttrNames, JsonNode exprAttrValues) {
        return switch (operand) {
            case PlaceholderOperand p -> {
                if (exprAttrValues != null) {
                    yield extractScalarValue(exprAttrValues.get(p.name()));
                }
                yield null;
            }
            case PathOperand path -> {
                String resolvedPath = resolvePathString(path, exprAttrNames);
                JsonNode attrNode = item != null ? resolveNestedAttribute(item, resolvedPath) : null;
                yield extractScalarValue(attrNode);
            }
            case FunctionOperand func -> {
                // size() returns a number
                if ("size".equalsIgnoreCase(func.functionName()) && !func.args().isEmpty()) {
                    String path = resolveAttributePath(func.args().getFirst(), exprAttrNames);
                    JsonNode attrNode = item != null ? resolveNestedAttribute(item, path) : null;
                    yield attrNode != null ? String.valueOf(computeSize(attrNode)) : null;
                }
                yield null;
            }
        };
    }

    /**
     * Resolves an operand to its raw DynamoDB attribute value node (for IN and contains).
     */
    private static JsonNode resolveAttributeValue(Operand operand, JsonNode item,
                                                    JsonNode exprAttrNames, JsonNode exprAttrValues) {
        return switch (operand) {
            case PlaceholderOperand p -> exprAttrValues != null ? exprAttrValues.get(p.name()) : null;
            case PathOperand path -> {
                String resolvedPath = resolvePathString(path, exprAttrNames);
                yield item != null ? resolveNestedAttribute(item, resolvedPath) : null;
            }
            case FunctionOperand func -> {
                if ("size".equalsIgnoreCase(func.functionName()) && !func.args().isEmpty()) {
                    String path = resolveAttributePath(func.args().getFirst(), exprAttrNames);
                    JsonNode attrNode = item != null ? resolveNestedAttribute(item, path) : null;
                    if (attrNode != null) {
                        ObjectNode numNode = JsonNodeFactory.instance.objectNode();
                        numNode.put("N", String.valueOf(computeSize(attrNode)));
                        yield numNode;
                    }
                }
                yield null;
            }
        };
    }

    // ── Attribute path resolution ──

    private static String resolveAttributePath(Operand operand, JsonNode exprAttrNames) {
        if (operand instanceof PathOperand path) {
            return resolvePathString(path, exprAttrNames);
        }
        return operand.toString();
    }

    private static String resolvePathString(PathOperand path, JsonNode exprAttrNames) {
        var sb = new StringBuilder();
        for (int i = 0; i < path.segments().size(); i++) {
            String segment = path.segments().get(i);
            if (segment.startsWith("[")) {
                // List index: append directly without preceding dot
                sb.append(segment);
            } else {
                if (i > 0) sb.append(".");
                String resolved = resolveAttributeName(segment, exprAttrNames);
                if (segment.startsWith("#") && resolved != null) {
                    resolved = resolved.replace(".", DOT_ESCAPE);
                }
                sb.append(resolved);
            }
        }
        return sb.toString();
    }

    // ── Helpers (self-contained, replicated from DynamoDbService) ──

    private static String resolveAttributeName(String nameOrPlaceholder, JsonNode exprAttrNames) {
        if (nameOrPlaceholder.startsWith("#") && exprAttrNames != null) {
            JsonNode resolved = exprAttrNames.get(nameOrPlaceholder);
            if (resolved != null) {
                return resolved.asText();
            }
        }
        return nameOrPlaceholder;
    }

    private static String extractScalarValue(JsonNode attrValue) {
        if (attrValue == null) return null;
        if (attrValue.has("S")) return attrValue.get("S").asText();
        if (attrValue.has("N")) return attrValue.get("N").asText();
        if (attrValue.has("B")) return attrValue.get("B").asText();
        if (attrValue.has("BOOL")) return attrValue.get("BOOL").asText();
        return attrValue.asText();
    }

    // Tokenizes a resolved path string (e.g. "a.b[0].c") into segments.
    // Each segment is either a plain attribute name or a "[n]" list index string.
    private static List<String> parsePathSegments(String path) {
        var segs = new ArrayList<String>();
        for (String dotPart : path.split("\\.")) {
            dotPart = dotPart.replace(DOT_ESCAPE, ".");
            int brk = dotPart.indexOf('[');
            if (brk < 0) {
                if (!dotPart.isEmpty()) segs.add(dotPart);
            } else {
                if (brk > 0) segs.add(dotPart.substring(0, brk));
                String rest = dotPart.substring(brk);
                int p = 0;
                while (p < rest.length() && rest.charAt(p) == '[') {
                    int close = rest.indexOf(']', p);
                    if (close < 0) break;
                    segs.add(rest.substring(p, close + 1)); // "[n]"
                    p = close + 1;
                }
            }
        }
        return segs;
    }

    private static JsonNode resolveNestedAttribute(JsonNode item, String path) {
        List<String> segments = parsePathSegments(path);
        JsonNode current = item;
        for (int i = 0; i < segments.size(); i++) {
            if (current == null) return null;
            String segment = segments.get(i);
            if (segment.matches("\\[\\d+\\]")) {
                // List index navigation
                int idx = Integer.parseInt(segment.substring(1, segment.length() - 1));
                JsonNode listNode = current.has("L") ? current.get("L") : (current.isArray() ? current : null);
                if (listNode == null || !listNode.isArray() || idx >= listNode.size()) return null;
                current = listNode.get(idx);
            } else if (i == 0) {
                current = current.get(segment);
            } else {
                if (current.has("M")) {
                    current = current.get("M").get(segment);
                } else {
                    current = current.get(segment);
                }
            }
        }
        return current;
    }

    static boolean attributeValuesEqual(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        for (String type : new String[]{"S", "B", "BOOL", "NULL"}) {
            if (a.has(type) && b.has(type)) {
                return a.get(type).asText().equals(b.get(type).asText());
            }
            if (a.has(type) || b.has(type)) return false;
        }
        if (a.has("N") && b.has("N")) {
            try {
                return new BigDecimal(a.get("N").asText())
                        .compareTo(new BigDecimal(b.get("N").asText())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (a.has("N") || b.has("N")) return false;
        if (a.has("M") && b.has("M")) {
            JsonNode aMap = a.get("M");
            JsonNode bMap = b.get("M");
            if (aMap.size() != bMap.size()) return false;
            var fields = aMap.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!bMap.has(entry.getKey())) return false;
                if (!attributeValuesEqual(entry.getValue(), bMap.get(entry.getKey()))) return false;
            }
            return true;
        }
        if (a.has("L") && b.has("L")) {
            JsonNode aList = a.get("L");
            JsonNode bList = b.get("L");
            if (aList.size() != bList.size()) return false;
            for (int i = 0; i < aList.size(); i++) {
                if (!attributeValuesEqual(aList.get(i), bList.get(i))) return false;
            }
            return true;
        }
        // Set types: SS, NS, BS — order-independent comparison
        if (a.has("SS") && b.has("SS")) {
            JsonNode aArr = a.get("SS"), bArr = b.get("SS");
            if (aArr.size() != bArr.size()) return false;
            var aSet = new java.util.HashSet<String>();
            aArr.forEach(e -> aSet.add(e.asText()));
            for (JsonNode e : bArr) { if (!aSet.contains(e.asText())) return false; }
            return true;
        }
        if (a.has("SS") || b.has("SS")) return false;
        if (a.has("BS") && b.has("BS")) {
            JsonNode aArr = a.get("BS"), bArr = b.get("BS");
            if (aArr.size() != bArr.size()) return false;
            var aSet = new java.util.HashSet<String>();
            aArr.forEach(e -> aSet.add(e.asText()));
            for (JsonNode e : bArr) { if (!aSet.contains(e.asText())) return false; }
            return true;
        }
        if (a.has("BS") || b.has("BS")) return false;
        if (a.has("NS") && b.has("NS")) {
            JsonNode aArr = a.get("NS"), bArr = b.get("NS");
            if (aArr.size() != bArr.size()) return false;
            var aSet = new java.util.HashSet<BigDecimal>();
            try {
                aArr.forEach(e -> aSet.add(new BigDecimal(e.asText())));
                for (JsonNode e : bArr) { if (!aSet.contains(new BigDecimal(e.asText()))) return false; }
            } catch (NumberFormatException ex) { return false; }
            return true;
        }
        if (a.has("NS") || b.has("NS")) return false;
        return false;
    }

    private static int compareValues(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    static int compareAttributeValues(JsonNode a, JsonNode b) {
        if (a.has("N") && b.has("N")) {
            try {
                return new BigDecimal(a.get("N").asText())
                        .compareTo(new BigDecimal(b.get("N").asText()));
            } catch (NumberFormatException e) {
                return a.get("N").asText().compareTo(b.get("N").asText());
            }
        }
        if (a.has("B") && b.has("B")) {
            byte[] aBytes = Base64.getDecoder().decode(a.get("B").asText());
            byte[] bBytes = Base64.getDecoder().decode(b.get("B").asText());
            int minLen = Math.min(aBytes.length, bBytes.length);
            for (int i = 0; i < minLen; i++) {
                int diff = (aBytes[i] & 0xFF) - (bBytes[i] & 0xFF);
                if (diff != 0) return diff;
            }
            return Integer.compare(aBytes.length, bBytes.length);
        }
        String aStr = a.has("S") ? a.get("S").asText() : extractScalarValue(a);
        String bStr = b.has("S") ? b.get("S").asText() : extractScalarValue(b);
        if (aStr == null) aStr = "";
        if (bStr == null) bStr = "";
        return aStr.compareTo(bStr);
    }

    private static int computeSize(JsonNode attrNode) {
        if (attrNode.has("S")) return attrNode.get("S").asText().length();
        if (attrNode.has("B")) return attrNode.get("B").asText().length(); // base64 length
        if (attrNode.has("L")) return attrNode.get("L").size();
        if (attrNode.has("M")) return attrNode.get("M").size();
        if (attrNode.has("SS")) return attrNode.get("SS").size();
        if (attrNode.has("NS")) return attrNode.get("NS").size();
        if (attrNode.has("BS")) return attrNode.get("BS").size();
        if (attrNode.has("N")) return attrNode.get("N").asText().length();
        return 0;
    }
}
