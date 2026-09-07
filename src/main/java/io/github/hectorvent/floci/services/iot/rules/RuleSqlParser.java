package io.github.hectorvent.floci.services.iot.rules;

import io.github.hectorvent.floci.services.iot.rules.RuleSql.Aliased;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Call;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Comparison;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Conjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Disjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Expr;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Literal;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Negation;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Operator;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Path;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Projection;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.SelectAll;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recursive descent parser for the subset of the AWS IoT rules SQL dialect Floci evaluates:
 *
 * <pre>
 * statement  := SELECT item (',' item)* FROM string [WHERE expr]
 * item       := '*' | operand [AS identifier]
 * expr       := term (OR term)*
 * term       := factor (AND factor)*
 * factor     := NOT factor | '(' expr ')' | operand [comparison operand]
 * comparison := '=' | '<>' | '!=' | '<' | '<=' | '>' | '>='
 * operand    := path | literal | call
 * call       := topic '(' [integer] ')' | (startswith | endswith) '(' operand ',' operand ')'
 * path       := identifier ('.' identifier)*
 * literal    := 'string' | "string" | number | TRUE | FALSE | NULL
 * </pre>
 *
 * Keywords and function names are case insensitive, field names are case sensitive, and anything
 * outside the grammar raises {@link RuleSqlParseException} naming the offending token.
 */
public final class RuleSqlParser {

    private static final Set<String> KEYWORDS =
            Set.of("SELECT", "FROM", "WHERE", "AS", "AND", "OR", "NOT", "TRUE", "FALSE", "NULL");

    /**
     * Bounds the work one statement can cost, counting real tokens only: {@link #tokenize} appends
     * an end marker. Real rules are a few dozen tokens, so this only stops a pathological statement
     * from driving the recursive descent, or the equally recursive evaluation of the tree it
     * produces, into a stack overflow.
     */
    private static final int MAX_TOKENS = 1000;

    private static final String TOPIC = "topic";
    private static final Set<String> STRING_PREDICATES = Set.of("startswith", "endswith");

    private final List<Token> tokens;
    private int index;

    private RuleSqlParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static RuleSql parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new RuleSqlParseException("Empty topic rule SQL statement", "", 0);
        }
        List<Token> tokens = tokenize(sql);
        if (tokens.size() - 1 > MAX_TOKENS) {
            Token beyondLimit = tokens.get(MAX_TOKENS);
            throw new RuleSqlParseException("Statement has more than " + MAX_TOKENS + " tokens",
                    beyondLimit.text(), beyondLimit.position());
        }
        return new RuleSqlParser(tokens).statement();
    }

    private RuleSql statement() {
        expectKeyword("SELECT");
        List<Projection> projections = new ArrayList<>();
        projections.add(projection());
        while (matchSymbol(",")) {
            projections.add(projection());
        }
        expectKeyword("FROM");
        Token filter = peek();
        if (filter.kind() != Kind.STRING || filter.text().isBlank()) {
            throw fail("Expected a quoted topic filter after FROM", filter);
        }
        advance();
        Expr where = matchKeyword("WHERE") ? expression() : null;
        Token trailing = peek();
        if (trailing.kind() != Kind.END) {
            throw fail("Unsupported trailing input", trailing);
        }
        return new RuleSql(projections, filter.text(), where);
    }

    private Projection projection() {
        if (matchSymbol("*")) {
            return new SelectAll();
        }
        Token start = peek();
        Expr expression = operand();
        if (matchKeyword("AS")) {
            Token alias = peek();
            if (alias.kind() != Kind.IDENTIFIER) {
                throw fail("Expected an alias after AS", alias);
            }
            advance();
            return new Aliased(expression, alias.text());
        }
        return new Aliased(expression, defaultAlias(expression, start));
    }

    private String defaultAlias(Expr expression, Token start) {
        return switch (expression) {
            case Path path -> path.segments().getLast();
            case Call call -> call.function();
            default -> throw fail("Select item needs an AS alias", start);
        };
    }

    private Expr expression() {
        Expr left = term();
        while (matchKeyword("OR")) {
            left = new Disjunction(left, term());
        }
        return left;
    }

    private Expr term() {
        Expr left = factor();
        while (matchKeyword("AND")) {
            left = new Conjunction(left, factor());
        }
        return left;
    }

    private Expr factor() {
        if (matchKeyword("NOT")) {
            return new Negation(factor());
        }
        if (matchSymbol("(")) {
            Expr grouped = expression();
            expectSymbol(")");
            return grouped;
        }
        Expr left = operand();
        Operator operator = comparisonOperator();
        return operator == null ? left : new Comparison(left, operator, operand());
    }

    private Operator comparisonOperator() {
        Token token = peek();
        if (token.kind() != Kind.SYMBOL) {
            return null;
        }
        Operator operator = switch (token.text()) {
            case "=" -> Operator.EQUAL;
            case "<>", "!=" -> Operator.NOT_EQUAL;
            case "<" -> Operator.LESS;
            case "<=" -> Operator.LESS_OR_EQUAL;
            case ">" -> Operator.GREATER;
            case ">=" -> Operator.GREATER_OR_EQUAL;
            default -> null;
        };
        if (operator != null) {
            advance();
        }
        return operator;
    }

    private Expr operand() {
        Token token = peek();
        switch (token.kind()) {
            case STRING -> {
                advance();
                return RuleSql.text(token.text());
            }
            case NUMBER -> {
                advance();
                return numberLiteral(token);
            }
            case KEYWORD -> {
                String keyword = token.text().toUpperCase(Locale.ROOT);
                if (keyword.equals("TRUE") || keyword.equals("FALSE")) {
                    advance();
                    return RuleSql.bool(keyword.equals("TRUE"));
                }
                if (keyword.equals("NULL")) {
                    advance();
                    return RuleSql.nullValue();
                }
                throw fail("Unexpected keyword", token);
            }
            case IDENTIFIER -> {
                advance();
                return peekIsSymbol("(") ? call(token) : path(token);
            }
            default -> throw fail("Expected a field, literal or function", token);
        }
    }

    private Literal numberLiteral(Token token) {
        try {
            return token.text().indexOf('.') < 0
                    ? RuleSql.number(Long.parseLong(token.text()))
                    : RuleSql.decimal(new BigDecimal(token.text()));
        } catch (NumberFormatException e) {
            throw fail("Number is out of range", token);
        }
    }

    private Expr path(Token first) {
        List<String> segments = new ArrayList<>();
        segments.add(first.text());
        while (matchSymbol(".")) {
            Token segment = peek();
            if (segment.kind() != Kind.IDENTIFIER) {
                throw fail("Expected a field name after '.'", segment);
            }
            advance();
            segments.add(segment.text());
        }
        return new Path(segments);
    }

    private Expr call(Token name) {
        String function = name.text().toLowerCase(Locale.ROOT);
        if (!TOPIC.equals(function) && !STRING_PREDICATES.contains(function)) {
            throw fail("Unsupported function", name);
        }
        expectSymbol("(");
        List<Expr> arguments = new ArrayList<>();
        if (!peekIsSymbol(")")) {
            arguments.add(operand());
            while (matchSymbol(",")) {
                arguments.add(operand());
            }
        }
        expectSymbol(")");
        if (TOPIC.equals(function)) {
            validateTopicArguments(arguments, name);
        } else if (arguments.size() != 2) {
            throw fail("Function " + name.text() + " takes two arguments", name);
        }
        return new Call(function, arguments);
    }

    private void validateTopicArguments(List<Expr> arguments, Token name) {
        if (arguments.isEmpty()) {
            return;
        }
        if (arguments.size() != 1
                || !(arguments.getFirst() instanceof Literal literal)
                || !literal.value().isIntegralNumber()
                || literal.value().asLong() < 1) {
            throw fail("topic() takes no argument or a segment number starting at 1", name);
        }
    }

    private Token peek() {
        return tokens.get(index);
    }

    private void advance() {
        if (peek().kind() != Kind.END) {
            index++;
        }
    }

    private boolean peekIsSymbol(String symbol) {
        return peek().kind() == Kind.SYMBOL && peek().text().equals(symbol);
    }

    private boolean matchSymbol(String symbol) {
        if (!peekIsSymbol(symbol)) {
            return false;
        }
        advance();
        return true;
    }

    private void expectSymbol(String symbol) {
        if (!matchSymbol(symbol)) {
            throw fail("Expected '" + symbol + "'", peek());
        }
    }

    private boolean matchKeyword(String keyword) {
        if (peek().kind() != Kind.KEYWORD || !peek().text().equalsIgnoreCase(keyword)) {
            return false;
        }
        advance();
        return true;
    }

    private void expectKeyword(String keyword) {
        if (!matchKeyword(keyword)) {
            throw fail("Expected " + keyword, peek());
        }
    }

    private RuleSqlParseException fail(String reason, Token token) {
        return new RuleSqlParseException(reason, token.text(), token.position());
    }

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                i = readString(sql, i, tokens);
            } else if (isNumberStart(sql, i)) {
                i = readNumber(sql, i, tokens);
            } else if (Character.isLetter(c) || c == '_') {
                i = readWord(sql, i, tokens);
            } else {
                i = readSymbol(sql, i, tokens);
            }
        }
        tokens.add(new Token(Kind.END, "", sql.length()));
        return tokens;
    }

    private static boolean isNumberStart(String sql, int i) {
        char c = sql.charAt(i);
        return Character.isDigit(c) || (c == '-' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1)));
    }

    /** A string in single or double quotes, as AWS's own examples use both; the quote is doubled to escape it. */
    private static int readString(String sql, int start, List<Token> tokens) {
        char quote = sql.charAt(start);
        StringBuilder value = new StringBuilder();
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c != quote) {
                value.append(c);
                i++;
            } else if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                value.append(quote);
                i += 2;
            } else {
                tokens.add(new Token(Kind.STRING, value.toString(), start));
                return i + 1;
            }
        }
        throw new RuleSqlParseException("Unterminated string literal", String.valueOf(quote), start);
    }

    private static int readNumber(String sql, int start, List<Token> tokens) {
        int i = start + 1;
        boolean decimalPoint = false;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !decimalPoint && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1))) {
                decimalPoint = true;
                i++;
            } else {
                break;
            }
        }
        tokens.add(new Token(Kind.NUMBER, sql.substring(start, i), start));
        return i;
    }

    private static int readWord(String sql, int start, List<Token> tokens) {
        int i = start;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        String word = sql.substring(start, i);
        Kind kind = KEYWORDS.contains(word.toUpperCase(Locale.ROOT)) ? Kind.KEYWORD : Kind.IDENTIFIER;
        tokens.add(new Token(kind, word, start));
        return i;
    }

    private static int readSymbol(String sql, int start, List<Token> tokens) {
        String two = start + 1 < sql.length() ? sql.substring(start, start + 2) : "";
        if (two.equals("<>") || two.equals("<=") || two.equals(">=") || two.equals("!=")) {
            tokens.add(new Token(Kind.SYMBOL, two, start));
            return start + 2;
        }
        String one = sql.substring(start, start + 1);
        if (!",.()*=<>".contains(one)) {
            throw new RuleSqlParseException("Unsupported character", one, start);
        }
        tokens.add(new Token(Kind.SYMBOL, one, start));
        return start + 1;
    }

    private enum Kind {
        IDENTIFIER, KEYWORD, STRING, NUMBER, SYMBOL, END
    }

    private record Token(Kind kind, String text, int position) {
    }
}
