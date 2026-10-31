package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Stream;

public class DynamoDbPartiQLParser {

    enum TType {
        SELECT, FROM, WHERE, INSERT, INTO, VALUE,
        UPDATE, SET, REMOVE, DELETE, AND, BETWEEN,
        IDENT, STRING, NUMBER, BOOL, NULL, QUESTION,
        EQ, NE, LT, LE, GT, GE,
        LPAREN, RPAREN, LBRACE, RBRACE, COMMA, COLON, DOT,
        OR, NOT, IN, IS, MISSING, RETURNING, ALL, MODIFIED, OLD, NEW,
        PLUS, MINUS, LBRACKET, RBRACKET,
        LBAG, RBAG, SEMICOLON,
        ORDER, BY, ASC, DESC,
        EOF
    }

    record Token(TType type, String value, int start) {
        Token(TType type, String value) {
            this(type, value, -1);
        }
    }

    // --- AST node types ---

    public sealed interface Seg permits Seg.Name, Seg.Index {
        record Name(String value)  implements Seg {}
        record Index(long value)   implements Seg {}
    }

    public record Path(List<Seg> segments) {

        public String root() {
            return ((Seg.Name) segments.getFirst()).value();
        }

        public boolean isRootOnly() {
            return segments.size() == 1;
        }

        String leafName() {
            int last = segments.size() - 1;
            while (segments.get(last) instanceof Seg.Index) {
                last--;
            }
            StringBuilder name = new StringBuilder(((Seg.Name) segments.get(last)).value());
            for (Seg trailing : segments.subList(last + 1, segments.size())) {
                name.append('[').append(((Seg.Index) trailing).value()).append(']');
            }
            return name.toString();
        }
    }

    public enum Returning {
        NONE("NONE"),
        ALL_OLD("ALL_OLD"),
        ALL_NEW("ALL_NEW"),
        MODIFIED_OLD("UPDATED_OLD"),
        MODIFIED_NEW("UPDATED_NEW");

        private final String returnValues;

        Returning(String returnValues) {
            this.returnValues = returnValues;
        }

        String returnValues() {
            return returnValues;
        }

        String clause() {
            return "RETURNING " + name().replace('_', ' ') + " *";
        }
    }

    record OrderTerm(String attribute, boolean descending) {}

    public sealed interface Stmt permits Stmt.Select, Stmt.Insert, Stmt.Update, Stmt.Delete, Stmt.Exists {
        String table();
        List<Cond> where();
        record Select(String table, String index, List<Path> columns, List<Cond> where,
                      List<OrderTerm> orderBy)                              implements Stmt {}
        record Insert(String table, Map<String, PVal> item)                 implements Stmt {
            @Override
            public List<Cond> where() {
                return List.of();
            }
        }
        record Update(String table, String index, List<SetClause> sets, List<Path> removes,
                      List<Cond> where, Returning returning)                implements Stmt {}
        record Delete(String table, String index, List<Cond> where, Returning returning) implements Stmt {}
        record Exists(Select select) implements Stmt {
            @Override
            public String table() {
                return select.table();
            }

            @Override
            public List<Cond> where() {
                return select.where();
            }
        }
    }

    /**
     * The IAM action for a single statement, using the same keyword tokens as execution. A statement
     * the tokenizer refuses has no action here, so it is authorized as ExecuteStatement and the
     * handler reports why it is malformed, as {@link #extractTable} leaves it.
     */
    public static String iamAction(String statement) {
        if (statement == null || statement.isBlank()) {
            return null;
        }
        List<Token> tokens;
        try {
            tokens = tokenize(statement.trim());
        } catch (Exception ignored) {
            // Execution reports malformed statements after credential and policy checks have run.
            return null;
        }
        return switch (tokens.getFirst().type()) {
            case SELECT -> "dynamodb:PartiQLSelect";
            case INSERT -> "dynamodb:PartiQLInsert";
            case UPDATE, FROM -> "dynamodb:PartiQLUpdate";
            case DELETE -> "dynamodb:PartiQLDelete";
            default -> null;
        };
    }

    /**
     * Extracts the target table name from a PartiQL statement using the parser tokenizer,
     * ensuring quoted attribute names containing SQL keywords are not mistakenly treated as table names.
     */
    public static String extractTable(String statement) {
        if (statement == null || statement.isBlank()) {
            return null;
        }
        try {
            return parse(statement, List.of()).table();
        } catch (Exception e) {
            try {
                List<Token> tokens = tokenize(statement.trim());
                for (int i = 0; i < tokens.size(); i++) {
                    Token t = tokens.get(i);
                    if (t.type() == TType.FROM || t.type() == TType.INTO || t.type() == TType.UPDATE) {
                        if (i + 1 < tokens.size()) {
                            Token next = tokens.get(i + 1);
                            if (next.type() == TType.IDENT || next.type() == TType.STRING) {
                                return next.value();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    sealed interface PVal permits PVal.Str, PVal.Num, PVal.Bool, PVal.Null, PVal.Av,
            PVal.ListOf, PVal.Tuple, PVal.Bag {
        record Str(String v)       implements PVal {}
        record Num(String v)       implements PVal {}
        record Bool(boolean v)     implements PVal {}
        record Null()              implements PVal {}
        // A parameter of a type with no literal syntax, kept as its wire node.
        record Av(String type, JsonNode node) implements PVal {}
        record ListOf(List<PVal> items)          implements PVal {}
        record Tuple(Map<String, PVal> fields)   implements PVal {}
        record Bag(String type, List<PVal> members) implements PVal {}
    }

    sealed interface Cond permits Cond.Leaf, Cond.And, Cond.Or, Cond.Not {

        default Optional<String> bareAttribute() {
            return Optional.empty();
        }

        sealed interface Leaf extends Cond permits Eq, Cmp, Between, BeginsWith, In, Missing,
                Contains, AttributeType, SizeCmp, IsType {
            Path path();

            @Override
            default Optional<String> bareAttribute() {
                return path().isRootOnly() ? Optional.of(path().root()) : Optional.empty();
            }
        }

        record Eq(Path path, PVal val)                          implements Leaf {}
        record Cmp(Path path, String op, PVal val)              implements Leaf {}
        record Between(Path path, PVal lo, PVal hi)             implements Leaf {}
        record BeginsWith(Path path, PVal prefix)               implements Leaf {}
        record In(Path path, List<PVal> values)                 implements Leaf {}
        record Missing(Path path, boolean negated)              implements Leaf {}
        record Contains(Path path, PVal operand)                implements Leaf {}
        record AttributeType(Path path, PVal type)              implements Leaf {}
        record SizeCmp(Path path, String op, PVal val)          implements Leaf {}
        record IsType(Path path, PartiQLType type, boolean negated) implements Leaf {}
        record And(List<Cond> operands)                         implements Cond {}
        record Or(List<Cond> operands)                          implements Cond {}
        record Not(Cond operand)                                implements Cond {}
    }

    enum PartiQLType {
        MISSING(0, null), NULL(0, "NULL"), BOOLEAN(0, "BOOL"), SMALLINT(0, null), INTEGER(0, null),
        INTEGER4(0, null), INTEGER8(0, null), FLOAT(1, null), REAL(0, null), DOUBLE_PRECISION(0, null),
        DECIMAL(2, null), NUMERIC(2, "N"), CHARACTER(1, null), CHARACTER_VARYING(1, null), STRING(0, "S"),
        SYMBOL(0, null), CLOB(0, null), BLOB(0, "B"), STRUCT(0, "M"), TUPLE(0, "M"), LIST(0, "L"),
        SEXP(0, null), BAG(0, null), DATE(0, null), TIME(1, null), TIME_WITH_TIME_ZONE(1, null),
        TIMESTAMP(0, null), ANY(0, null);

        private final int maxArity;
        private final String attributeType;

        PartiQLType(int maxArity, String attributeType) {
            this.maxArity = maxArity;
            this.attributeType = attributeType;
        }

        String attributeType() {
            return attributeType;
        }

        boolean supported() {
            return this == MISSING || attributeType != null;
        }

        String className() {
            StringBuilder name = new StringBuilder();
            for (String word : name().split("_")) {
                name.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
            return name.append("Type").toString();
        }
    }

    sealed interface Operand permits Operand.Value, Operand.Attribute, Operand.ListAppend {
        record Value(PVal val)      implements Operand {}
        record Attribute(Path path) implements Operand {}
        record ListAppend(Operand first, Operand second) implements Operand {}
    }

    sealed interface SetClause permits Assign, SetAdd, SetDelete {
        Path path();
    }

    record Assign(Path path, Operand left, String op, Operand right) implements SetClause {}
    record SetAdd(Path path, PVal bag)                               implements SetClause {}
    record SetDelete(Path path, PVal bag)                            implements SetClause {}

    // --- Tokenizer ---

    private static final String NOT_WELL_FORMED = "Statement wasn't well formed, can't be processed: ";

    static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0, n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (input.startsWith("--", i)) {
                while (i < n && input.charAt(i) != '\n' && input.charAt(i) != '\r') i++;
                continue;
            }
            if (input.startsWith("/*", i)) {
                int end = input.indexOf("*/", i + 2);
                if (end < 0) {
                    throw validationEx(NOT_WELL_FORMED);
                }
                i = end + 2;
                continue;
            }
            if (c == '\'') {
                StringBuilder text = new StringBuilder();
                i++;
                while (i < n && (input.charAt(i) != '\'' || (i + 1 < n && input.charAt(i + 1) == '\''))) {
                    text.append(input.charAt(i));
                    i += input.charAt(i) == '\'' ? 2 : 1;
                }
                tokens.add(new Token(TType.STRING, text.toString()));
                i++;
                continue;
            }
            if (c == '"') {
                int start = ++i;
                while (i < n && input.charAt(i) != '"') i++;
                tokens.add(new Token(TType.IDENT, input.substring(start, i), start - 1));
                i++;
                continue;
            }
            if (c == '?') { tokens.add(new Token(TType.QUESTION, "?", i)); i++; continue; }
            if (c == '*') { tokens.add(new Token(TType.IDENT, "*", i)); i++; continue; }
            if (c == '=') { tokens.add(new Token(TType.EQ, "=")); i++; continue; }
            if (c == '!' && i + 1 < n && input.charAt(i + 1) == '=') { tokens.add(new Token(TType.NE, "!=")); i += 2; continue; }
            if (c == '<' && i + 1 < n && input.charAt(i + 1) == '<') { tokens.add(new Token(TType.LBAG, "<<")); i += 2; continue; }
            if (c == '>' && i + 1 < n && input.charAt(i + 1) == '>') { tokens.add(new Token(TType.RBAG, ">>")); i += 2; continue; }
            if (c == '<') {
                if (i + 1 < n && input.charAt(i + 1) == '>') { tokens.add(new Token(TType.NE, "<>")); i += 2; }
                else if (i + 1 < n && input.charAt(i + 1) == '=') { tokens.add(new Token(TType.LE, "<=")); i += 2; }
                else { tokens.add(new Token(TType.LT, "<")); i++; }
                continue;
            }
            if (c == '>') {
                if (i + 1 < n && input.charAt(i + 1) == '=') { tokens.add(new Token(TType.GE, ">=")); i += 2; }
                else { tokens.add(new Token(TType.GT, ">")); i++; }
                continue;
            }
            if (c == '(') { tokens.add(new Token(TType.LPAREN, "(")); i++; continue; }
            if (c == ')') { tokens.add(new Token(TType.RPAREN, ")")); i++; continue; }
            if (c == '{') { tokens.add(new Token(TType.LBRACE, "{")); i++; continue; }
            if (c == '}') { tokens.add(new Token(TType.RBRACE, "}")); i++; continue; }
            if (c == '[') { tokens.add(new Token(TType.LBRACKET, "[")); i++; continue; }
            if (c == ']') { tokens.add(new Token(TType.RBRACKET, "]")); i++; continue; }
            if (c == ',') { tokens.add(new Token(TType.COMMA, ",")); i++; continue; }
            if (c == ':') { tokens.add(new Token(TType.COLON, ":")); i++; continue; }
            if (c == ';') { tokens.add(new Token(TType.SEMICOLON, ";")); i++; continue; }
            if (startsNumber(input, i, tokens)) {
                int start = i;
                if (c == '-') i++;
                boolean pointSeen = false;
                while (i < n && (Character.isDigit(input.charAt(i)) || (input.charAt(i) == '.' && !pointSeen))) {
                    pointSeen |= input.charAt(i) == '.';
                    i++;
                }
                i = skipExponent(input, i);
                String number = input.substring(start, i);
                requireRepresentable(number);
                tokens.add(new Token(TType.NUMBER, number, start));
                continue;
            }
            if (c == '.') { tokens.add(new Token(TType.DOT, ".")); i++; continue; }
            if (c == '+') { tokens.add(new Token(TType.PLUS, "+")); i++; continue; }
            if (c == '-') { tokens.add(new Token(TType.MINUS, "-")); i++; continue; }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) i++;
                String word = input.substring(start, i);
                TType type = switch (word.toUpperCase()) {
                    case "SELECT"        -> TType.SELECT;
                    case "FROM"          -> TType.FROM;
                    case "WHERE"         -> TType.WHERE;
                    case "INSERT"        -> TType.INSERT;
                    case "INTO"          -> TType.INTO;
                    case "VALUE"         -> TType.VALUE;
                    case "UPDATE"        -> TType.UPDATE;
                    case "SET"           -> TType.SET;
                    case "REMOVE"        -> TType.REMOVE;
                    case "DELETE"        -> TType.DELETE;
                    case "AND"           -> TType.AND;
                    case "OR"            -> TType.OR;
                    case "NOT"           -> TType.NOT;
                    case "IN"            -> TType.IN;
                    case "IS"            -> TType.IS;
                    case "MISSING"       -> TType.MISSING;
                    case "BETWEEN"       -> TType.BETWEEN;
                    case "RETURNING"     -> TType.RETURNING;
                    case "ALL"           -> TType.ALL;
                    case "MODIFIED"      -> TType.MODIFIED;
                    case "OLD"           -> TType.OLD;
                    case "NEW"           -> TType.NEW;
                    case "ORDER"         -> TType.ORDER;
                    case "BY"            -> TType.BY;
                    case "ASC"           -> TType.ASC;
                    case "DESC"          -> TType.DESC;
                    case "TRUE", "FALSE" -> TType.BOOL;
                    case "NULL"          -> TType.NULL;
                    default              -> TType.IDENT;
                };
                tokens.add(new Token(type, word, start));
                continue;
            }
            throw validationEx("Unexpected character '" + c + "' in PartiQL statement");
        }
        tokens.add(new Token(TType.EOF, ""));
        return tokens;
    }

    private static int skipExponent(String input, int i) {
        int n = input.length();
        if (i >= n || (input.charAt(i) != 'e' && input.charAt(i) != 'E')) {
            return i;
        }
        int digits = i + 1;
        if (digits < n && (input.charAt(digits) == '+' || input.charAt(digits) == '-')) {
            digits++;
        }
        if (digits >= n || !Character.isDigit(input.charAt(digits))) {
            return i;
        }
        while (digits < n && Character.isDigit(input.charAt(digits))) {
            digits++;
        }
        return digits;
    }

    // A literal may start with its point (.5) or its sign (-5, -.5). After an operand a
    // point is a path step and a minus is an operator.
    private static boolean startsNumber(String input, int i, List<Token> tokens) {
        char c = input.charAt(i);
        if (Character.isDigit(c)) {
            return true;
        }
        if (endsOperand(tokens)) {
            return false;
        }
        if (c == '-') {
            return i + 1 < input.length() && startsUnsignedNumber(input, i + 1);
        }
        return startsUnsignedNumber(input, i);
    }

    private static boolean startsUnsignedNumber(String input, int i) {
        char c = input.charAt(i);
        return Character.isDigit(c)
                || (c == '.' && i + 1 < input.length() && Character.isDigit(input.charAt(i + 1)));
    }

    // DynamoDB reads the exponent as a 32-bit integer and refuses a literal whose exponent,
    // or whose scale once the exponent is applied, does not fit one.
    private static void requireRepresentable(String number) {
        int exponentAt = Math.max(number.indexOf('e'), number.indexOf('E'));
        try {
            if (exponentAt >= 0) {
                Integer.parseInt(number.substring(exponentAt + 1));
            }
            new BigDecimal(number);
        } catch (NumberFormatException expected) {
            throw validationEx(NOT_WELL_FORMED);
        }
    }

    // --- Recursive-descent parser ---

    private final String statement;
    private final List<Token> tokens;
    private final List<JsonNode> parameters;
    private int pos = 0;
    private int paramIdx = 0;
    // DynamoDB reports these only once the whole statement parses, and the first one in the statement wins.
    private String deferredError;

    private DynamoDbPartiQLParser(String statement, List<JsonNode> parameters) {
        this.statement = statement;
        this.tokens = tokenize(statement);
        this.parameters = parameters;
    }

    static Stmt parse(String statement, List<JsonNode> parameters) {
        parameters.forEach(DynamoDbAttributeValueValidator::validate);
        DynamoDbPartiQLParser parser = new DynamoDbPartiQLParser(statement, parameters);
        Stmt stmt = parser.parseStmt();
        if (parser.peek().type() == TType.SEMICOLON) {
            parser.advance();
        }
        if (parser.peek().type() != TType.EOF) {
            throw validationEx("Statement wasn't well formed, can't be processed: Unexpected token after expression");
        }
        if (parser.deferredError != null) {
            throw validationEx(parser.deferredError);
        }
        stmt.where().stream()
                .flatMap(DynamoDbPartiQLParser::leaves)
                .filter(Cond.In.class::isInstance)
                .map(Cond.In.class::cast)
                .filter(in -> in.values().size() > MAX_IN_OPERANDS)
                .findFirst()
                .ifPresent(in -> {
                    throw validationEx("The IN operator is provided with too many operands; number of operands: "
                            + in.values().size());
                });
        return stmt;
    }

    private static final int MAX_IN_OPERANDS = 100;

    static Stream<Cond.Leaf> leaves(Cond cond) {
        return switch (cond) {
            case Cond.Leaf leaf -> Stream.of(leaf);
            case Cond.Not not   -> leaves(not.operand());
            case Cond.And and   -> and.operands().stream().flatMap(DynamoDbPartiQLParser::leaves);
            case Cond.Or or     -> or.operands().stream().flatMap(DynamoDbPartiQLParser::leaves);
        };
    }

    private static final Set<TType> OPERAND_ENDS = EnumSet.of(TType.IDENT, TType.NUMBER, TType.STRING,
            TType.BOOL, TType.NULL, TType.QUESTION, TType.RPAREN, TType.RBRACKET, TType.RBRACE, TType.RBAG);

    private static boolean endsOperand(List<Token> tokens) {
        return !tokens.isEmpty() && OPERAND_ENDS.contains(tokens.getLast().type());
    }

    private Stmt parseStmt() {
        if (peekFunction("exists")) {
            advance();
            consume(TType.LPAREN);
            requireTermStart();
            Stmt.Select select = parseSelect();
            consume(TType.RPAREN);
            return new Stmt.Exists(select);
        }
        return switch (peek().type()) {
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case FROM -> parseFromFirstUpdate();
            case DELETE -> parseDelete();
            default -> {
                requireTermStart();
                throw validationEx(NOT_WELL_FORMED + "Expected data manipulation");
            }
        };
    }

    // SELECT col [, col …] | * FROM "Table"["." "Index"] [WHERE cond [AND cond …]]
    private Stmt.Select parseSelect() {
        consume(TType.SELECT);
        List<Path> cols = new ArrayList<>();
        if (peek().type() == TType.IDENT && "*".equals(peek().value())) {
            Token star = advance();
            if (isStar(star) && peek().type() == TType.COMMA) {
                throw validationEx(NOT_WELL_FORMED + STAR_WITH_OTHER_ITEMS);
            }
        } else {
            cols.add(parseSelectItem());
            while (peek().type() == TType.COMMA) { advance(); cols.add(parseSelectItem()); }
        }
        consume(TType.FROM);
        requireTermStart();
        String table = expectTableName();
        String index = parseIndexQualifier();
        List<Cond> where = new ArrayList<>();
        if (peek().type() == TType.WHERE) { advance(); where = parseConditions(); }
        return new Stmt.Select(table, index, cols, where, parseOrderBy());
    }

    private static final String STAR_WITH_OTHER_ITEMS =
            "Other expressions may not be present in the select list when '*' is used without dot notation.";

    private Path parseSelectItem() {
        if (peek().type() == TType.FROM) {
            throw validationEx(NOT_WELL_FORMED
                    + "Expected identifier or an expression but found unexpected keyword 'from' in a select list.");
        }
        if (isStar(peek())) {
            throw validationEx(NOT_WELL_FORMED + STAR_WITH_OTHER_ITEMS);
        }
        return parsePath();
    }

    private List<OrderTerm> parseOrderBy() {
        if (peek().type() != TType.ORDER) {
            return List.of();
        }
        advance();
        consume(TType.BY);
        List<OrderTerm> terms = new ArrayList<>();
        do {
            if (!terms.isEmpty()) {
                advance();
            }
            requireTermStart();
            String attribute = expectIdent();
            boolean descending = peek().type() == TType.DESC;
            if (descending || peek().type() == TType.ASC) {
                advance();
            }
            terms.add(new OrderTerm(attribute, descending));
        } while (peek().type() == TType.COMMA);
        return List.copyOf(terms);
    }

    // INSERT INTO "Table" VALUE {'key': val, …}
    private Stmt.Insert parseInsert() {
        consume(TType.INSERT);
        consume(TType.INTO);
        requireSimplePathStart();
        String table = expectTableName();
        if (peek().type() == TType.DOT) {
            throw validationEx("FROM clause may only contain a single table name");
        }
        if (peek().type() != TType.VALUE) {
            requireTermStart();
        }
        consume(TType.VALUE);
        requireTermStart();
        consume(TType.LBRACE);
        return new Stmt.Insert(table, parseTupleFields());
    }

    private Map<String, PVal> parseTupleFields() {
        Map<String, PVal> fields = new LinkedHashMap<>();
        while (peek().type() != TType.RBRACE) {
            String key = parseFieldName();
            fields.put(key, parseValue(null));
            if (peek().type() == TType.COMMA) {
                advance();
            }
        }
        consume(TType.RBRACE);
        return fields;
    }

    // UPDATE "Table" SET attr=val [, …] [REMOVE attr [, …]] WHERE …
    private Stmt.Update parseUpdate() {
        consume(TType.UPDATE);
        requireSimplePathStart();
        String table = expectTableName();
        String index = parseIndexQualifier();
        Edits edits = parseEdits();
        // DynamoDB checks the WHERE clause before the SET values.
        String setError = deferredError;
        deferredError = null;
        consume(TType.WHERE);
        List<Cond> where = parseConditions();
        defer(setError);
        return new Stmt.Update(table, index, edits.sets(), edits.removes(), where, parseReturning());
    }

    // FROM "Table" [WHERE …] SET attr=val [, …] [REMOVE attr [, …]], an UPDATE with the clauses in PartiQL's order
    private Stmt.Update parseFromFirstUpdate() {
        consume(TType.FROM);
        requireTermStart();
        String table = expectTableName();
        String index = parseIndexQualifier();
        List<Cond> where = List.of();
        if (peek().type() == TType.WHERE) { advance(); where = parseConditions(); }
        Edits edits = parseEdits();
        return new Stmt.Update(table, index, edits.sets(), edits.removes(), where, parseReturning());
    }

    private record Edits(List<SetClause> sets, List<Path> removes) {}

    private Edits parseEdits() {
        if (peek().type() != TType.SET && peek().type() != TType.REMOVE) {
            throw validationEx(NOT_WELL_FORMED + "Expected data manipulation");
        }
        List<SetClause> sets = new ArrayList<>();
        List<Path> removes = new ArrayList<>();
        while (peek().type() == TType.SET || peek().type() == TType.REMOVE) {
            if (peek().type() == TType.SET) {
                advance();
                sets.add(parseAssign());
                while (peek().type() == TType.COMMA) { advance(); sets.add(parseAssign()); }
            } else {
                advance();
                removes.add(parseSimplePath());
                while (peek().type() == TType.COMMA) { advance(); removes.add(parseSimplePath()); }
            }
        }
        return new Edits(sets, removes);
    }

    private Stmt.Delete parseDelete() {
        consume(TType.DELETE);
        consume(TType.FROM);
        requireSimplePathStart();
        String table = expectTableName();
        String index = parseIndexQualifier();
        consume(TType.WHERE);
        List<Cond> where = parseConditions();
        Returning returning = parseReturning();
        if (returning != Returning.NONE && returning != Returning.ALL_OLD) {
            throw validationEx("Invalid returning clause: " + returning.clause()
                    + ". Only RETURNING ALL OLD * is allowed in DELETE statements.");
        }
        return new Stmt.Delete(table, index, where, returning);
    }

    private String expectTableName() {
        String table = expectIdent();
        requireNonEmptyPathComponent(table);
        return table;
    }

    private String parseIndexQualifier() {
        if (peek().type() != TType.DOT) {
            return null;
        }
        advance();
        String index = expectIdent();
        requireNonEmptyPathComponent(index);
        if (peek().type() == TType.DOT) {
            throw validationEx("A path may contain at most 2 components in the FROM clause");
        }
        return index;
    }

    private static void requireNonEmptyPathComponent(String component) {
        if (component.isEmpty()) {
            throw validationEx("Path component cannot be an empty string");
        }
    }

    private Returning parseReturning() {
        if (peek().type() != TType.RETURNING) {
            return Returning.NONE;
        }
        advance();
        TType scope = advance().type();
        TType age = advance().type();
        Returning returning = returningOf(scope, age);
        Token star = advance();
        if (!"*".equals(star.value())) {
            throw validationEx("Expected * in the RETURNING clause, got: '" + star.value() + "'");
        }
        return returning;
    }

    private static Returning returningOf(TType scope, TType age) {
        if (scope == TType.ALL && age == TType.OLD) {
            return Returning.ALL_OLD;
        }
        if (scope == TType.ALL && age == TType.NEW) {
            return Returning.ALL_NEW;
        }
        if (scope == TType.MODIFIED && age == TType.OLD) {
            return Returning.MODIFIED_OLD;
        }
        if (scope == TType.MODIFIED && age == TType.NEW) {
            return Returning.MODIFIED_NEW;
        }
        throw validationEx("Expected ALL or MODIFIED and OLD or NEW in the RETURNING clause");
    }

    private List<Cond> parseConditions() {
        Cond cond = parseOr();
        return cond instanceof Cond.And and ? and.operands() : List.of(cond);
    }

    private Cond parseOr() {
        List<Cond> operands = new ArrayList<>();
        operands.add(parseAnd());
        while (peek().type() == TType.OR) {
            advance();
            requireRightHandSide();
            operands.add(parseAnd());
        }
        return operands.size() == 1 ? operands.getFirst() : new Cond.Or(List.copyOf(operands));
    }

    private Cond parseAnd() {
        List<Cond> operands = new ArrayList<>();
        addAndOperand(operands, parseNot());
        while (peek().type() == TType.AND) {
            advance();
            requireRightHandSide();
            addAndOperand(operands, parseNot());
        }
        return operands.size() == 1 ? operands.getFirst() : new Cond.And(List.copyOf(operands));
    }

    private static void addAndOperand(List<Cond> operands, Cond operand) {
        if (operand instanceof Cond.And and) {
            operands.addAll(and.operands());
        } else {
            operands.add(operand);
        }
    }

    private Cond parseNot() {
        if (peek().type() == TType.NOT) {
            advance();
            return new Cond.Not(parseNot());
        }
        return parseComparison();
    }

    private static final Set<TType> COMPARISONS = EnumSet.of(TType.EQ, TType.NE, TType.LT, TType.LE, TType.GT, TType.GE);

    private static final List<String> PREDICATE_FUNCTIONS =
            List.of("begins_with", "contains", "attribute_type", "attribute_exists", "attribute_not_exists");

    private Cond parseComparison() {
        if (peek().type() == TType.BOOL
                && (tokens.get(pos + 1).type() == TType.EQ || tokens.get(pos + 1).type() == TType.NE)) {
            boolean flag = Boolean.parseBoolean(advance().value());
            boolean equal = advance().type() == TType.EQ;
            requireRightHandSide();
            Cond predicate = parseCond();
            return flag == equal ? predicate : new Cond.Not(predicate);
        }
        Cond cond = parseCond();
        if (peek().type() == TType.BETWEEN) {
            throw incorrectOperandType("BETWEEN", "BOOL");
        }
        if (peek().type() == TType.IN) {
            advance();
            requireRightHandSide();
            requireTermStart();
            consume(TType.LBRACKET);
            List<Cond> matches = new ArrayList<>();
            matches.add(conditionEquals(cond));
            while (peek().type() == TType.COMMA) {
                advance();
                matches.add(conditionEquals(cond));
            }
            consume(TType.RBRACKET);
            return matches.size() == 1 ? matches.getFirst() : new Cond.Or(List.copyOf(matches));
        }
        if (!COMPARISONS.contains(peek().type())) {
            return cond;
        }
        String op = parseOp();
        requireRightHandSide();
        if (!"=".equals(op) && !"<>".equals(op)) {
            throw incorrectOperandType(op, "BOOL");
        }
        Cond equal = conditionEquals(cond);
        return "=".equals(op) ? equal : new Cond.Not(equal);
    }

    private Cond conditionEquals(Cond cond) {
        boolean predicateNext = peek().type() == TType.LPAREN
                || PREDICATE_FUNCTIONS.stream().anyMatch(this::peekFunction);
        if (predicateNext) {
            Cond other = parseCond();
            return sameTruth(cond, other, new Cond.Not(other));
        }
        if (peek().type() == TType.IDENT) {
            Path path = parsePath();
            return sameTruth(cond, new Cond.Eq(path, new PVal.Bool(true)), new Cond.Eq(path, new PVal.Bool(false)));
        }
        if (parseValue() instanceof PVal.Bool flag) {
            return flag.v() ? cond : new Cond.Not(cond);
        }
        return new Cond.And(List.of(cond, new Cond.Not(cond)));
    }

    private static Cond sameTruth(Cond cond, Cond whenTrue, Cond whenFalse) {
        return new Cond.Or(List.of(new Cond.And(List.of(cond, whenTrue)),
                new Cond.And(List.of(new Cond.Not(cond), whenFalse))));
    }

    // S, N and B are the only types DynamoDB gives an ordering.
    static final Set<String> ORDERED_TYPES = Set.of("S", "N", "B");

    static String typeCode(PVal val) {
        return switch (val) {
            case PVal.Str ignored  -> "S";
            case PVal.Num ignored  -> "N";
            case PVal.Bool ignored -> "BOOL";
            case PVal.Null ignored -> "NULL";
            case PVal.Av av        -> av.type();
            case PVal.ListOf ignored -> "L";
            case PVal.Tuple ignored  -> "M";
            case PVal.Bag bag        -> bag.type();
        };
    }

    private void requireOrdered(String op, PVal val) {
        if (!literalErrorPending() && !ORDERED_TYPES.contains(typeCode(val))) {
            throw incorrectOperandType(op, val);
        }
    }

    private static AwsException incorrectOperandType(String op, PVal val) {
        return incorrectOperandType(op, typeCode(val));
    }

    private static AwsException incorrectOperandType(String op, String type) {
        return validationEx("Incorrect operand type for operator or function; "
                + "operator or function: " + op + ", operand type: " + type);
    }

    private Cond parseCond() {
        if (peek().type() == TType.LPAREN) {
            advance();
            Cond grouped = parseOr();
            consume(TType.RPAREN);
            return grouped;
        }
        if (peek().type() == TType.IDENT && "begins_with".equalsIgnoreCase(peek().value())) {
            advance();
            consume(TType.LPAREN);
            Path path = parsePath();
            consume(TType.COMMA);
            PVal prefix = parseValue();
            consume(TType.RPAREN);
            return new Cond.BeginsWith(path, prefix);
        }
        if (peekFunction("contains")) {
            advance();
            consume(TType.LPAREN);
            Path path = parsePath();
            consume(TType.COMMA);
            PVal operand = parseValue();
            consume(TType.RPAREN);
            return new Cond.Contains(path, operand);
        }
        if (peekFunction("attribute_type")) {
            advance();
            consume(TType.LPAREN);
            Path path = parsePath();
            consume(TType.COMMA);
            PVal type = parseValue();
            consume(TType.RPAREN);
            requireAttributeTypeName(type);
            return new Cond.AttributeType(path, type);
        }
        if (peekFunction("attribute_exists") || peekFunction("attribute_not_exists")) {
            boolean exists = "attribute_exists".equalsIgnoreCase(advance().value());
            consume(TType.LPAREN);
            Path path = parsePath();
            consume(TType.RPAREN);
            return new Cond.Missing(path, exists);
        }
        if (peekFunction("size")) {
            advance();
            consume(TType.LPAREN);
            Path path = parsePath();
            consume(TType.RPAREN);
            String op = parseOp();
            requireRightHandSide();
            PVal val = parseValue();
            if (!"=".equals(op) && !"<>".equals(op)) {
                requireOrdered(op, val);
            }
            return new Cond.SizeCmp(path, op, val);
        }
        Token operand = peek();
        Path path = parsePath();
        if (peek().type() == TType.BETWEEN) {
            advance();
            requireRightHandSide();
            PVal lo = parseValue();
            consume(TType.AND);
            if (peek().type() == TType.EOF || peek().type() == TType.SEMICOLON) {
                throw validationEx(NOT_WELL_FORMED + "Expected expression after AND");
            }
            PVal hi = parseValue();
            requireOrdered("BETWEEN", lo);
            requireOrdered("BETWEEN", hi);
            return new Cond.Between(path, lo, hi);
        }
        if (peek().type() == TType.IN) {
            advance();
            requireRightHandSide();
            requireTermStart();
            consume(TType.LBRACKET);
            List<PVal> values = new ArrayList<>();
            values.add(parseValue());
            while (peek().type() == TType.COMMA) {
                advance();
                values.add(parseValue());
            }
            consume(TType.RBRACKET);
            return new Cond.In(path, List.copyOf(values));
        }
        if (peek().type() == TType.IS) {
            advance();
            boolean negated = peek().type() == TType.NOT;
            if (negated) {
                advance();
            }
            PartiQLType type = parseTypeName(negated);
            if (!type.supported()) {
                defer("Unsupported data type for IS operator: " + type.className()
                        + ". Supported data types are [MISSING, NULL, BOOLEAN, BLOB, STRING, NUMERIC, STRUCT, TUPLE,"
                        + " LIST] at " + position(operand));
            }
            return type == PartiQLType.MISSING ? new Cond.Missing(path, negated) : new Cond.IsType(path, type, negated);
        }
        String op = parseOp();
        requireRightHandSide();
        PVal val = parseValue();
        if ("=".equals(op)) {
            return new Cond.Eq(path, val);
        }
        if (!"<>".equals(op)) {
            requireOrdered(op, val);
        }
        return new Cond.Cmp(path, op, val);
    }

    private boolean peekFunction(String name) {
        return peek().type() == TType.IDENT && name.equalsIgnoreCase(peek().value())
                && tokens.get(pos + 1).type() == TType.LPAREN;
    }

    private static final Map<String, PartiQLType> TYPE_NAMES = Map.ofEntries(
            Map.entry("MISSING", PartiQLType.MISSING), Map.entry("NULL", PartiQLType.NULL),
            Map.entry("BOOL", PartiQLType.BOOLEAN), Map.entry("BOOLEAN", PartiQLType.BOOLEAN),
            Map.entry("SMALLINT", PartiQLType.SMALLINT), Map.entry("INT2", PartiQLType.SMALLINT),
            Map.entry("INTEGER2", PartiQLType.SMALLINT), Map.entry("INT", PartiQLType.INTEGER),
            Map.entry("INTEGER", PartiQLType.INTEGER), Map.entry("INT4", PartiQLType.INTEGER4),
            Map.entry("INTEGER4", PartiQLType.INTEGER4), Map.entry("INT8", PartiQLType.INTEGER8),
            Map.entry("INTEGER8", PartiQLType.INTEGER8), Map.entry("BIGINT", PartiQLType.INTEGER8),
            Map.entry("FLOAT", PartiQLType.FLOAT), Map.entry("REAL", PartiQLType.REAL),
            Map.entry("DECIMAL", PartiQLType.DECIMAL), Map.entry("DEC", PartiQLType.DECIMAL),
            Map.entry("NUMERIC", PartiQLType.NUMERIC), Map.entry("CHAR", PartiQLType.CHARACTER),
            Map.entry("CHARACTER", PartiQLType.CHARACTER), Map.entry("VARCHAR", PartiQLType.CHARACTER_VARYING),
            Map.entry("STRING", PartiQLType.STRING), Map.entry("SYMBOL", PartiQLType.SYMBOL),
            Map.entry("CLOB", PartiQLType.CLOB), Map.entry("BLOB", PartiQLType.BLOB),
            Map.entry("STRUCT", PartiQLType.STRUCT), Map.entry("TUPLE", PartiQLType.TUPLE),
            Map.entry("LIST", PartiQLType.LIST), Map.entry("SEXP", PartiQLType.SEXP),
            Map.entry("BAG", PartiQLType.BAG), Map.entry("DATE", PartiQLType.DATE),
            Map.entry("TIME", PartiQLType.TIME), Map.entry("TIMESTAMP", PartiQLType.TIMESTAMP),
            Map.entry("ANY", PartiQLType.ANY));

    private PartiQLType parseTypeName(boolean negated) {
        Token name = advance();
        PartiQLType type = TYPE_NAMES.get(name.value().toUpperCase(Locale.ROOT));
        if (type == null && isWord(name, "DOUBLE") && isWord(peek(), "PRECISION")) {
            advance();
            type = PartiQLType.DOUBLE_PRECISION;
        } else if (type == null || !isBareWord(name)) {
            throw validationEx(NOT_WELL_FORMED + "Expected type name");
        }
        if (type == PartiQLType.CHARACTER && isWord(peek(), "VARYING")) {
            advance();
            type = PartiQLType.CHARACTER_VARYING;
        }
        List<Token> parameters = parseTypeParameters();
        if (parameters.size() > type.maxArity) {
            throw validationEx(NOT_WELL_FORMED + (negated ? "is_not" : "is") + " type argument "
                    + type.name().toLowerCase(Locale.ROOT) + " must have arity of 0.." + type.maxArity);
        }
        parameters.forEach(DynamoDbPartiQLParser::requireTypeParameter);
        if (type == PartiQLType.TIME && Stream.of("WITH", "TIME", "ZONE").anyMatch(word -> isWord(peek(), word))) {
            expectWord("WITH");
            expectWord("TIME");
            expectWord("ZONE");
            type = PartiQLType.TIME_WITH_TIME_ZONE;
        }
        return type;
    }

    private List<Token> parseTypeParameters() {
        List<Token> parameters = new ArrayList<>();
        if (peek().type() != TType.LPAREN) {
            return parameters;
        }
        advance();
        parameters.add(parseTypeParameter());
        while (peek().type() == TType.COMMA) {
            advance();
            parameters.add(parseTypeParameter());
        }
        if (advance().type() != TType.RPAREN) {
            throw validationEx(NOT_WELL_FORMED + "Expected RIGHT_PAREN");
        }
        return parameters;
    }

    private Token parseTypeParameter() {
        Token sign = peek().type() == TType.PLUS || peek().type() == TType.MINUS ? advance() : null;
        Token term = advance();
        if (term.type() == TType.RPAREN || term.type() == TType.COMMA || term.type() == TType.EOF) {
            throw validationEx(NOT_WELL_FORMED + "Unexpected term");
        }
        return sign == null || sign.type() == TType.PLUS ? term : sign;
    }

    // A literal too large for a long is not read as an integer.
    private static void requireTypeParameter(Token parameter) {
        boolean integer = parameter.type() == TType.NUMBER
                && parameter.value().chars().allMatch(c -> '0' <= c && c <= '9');
        int bits = integer ? new BigInteger(parameter.value()).bitLength() : Long.SIZE;
        if (bits >= Long.SIZE) {
            throw validationEx(NOT_WELL_FORMED + "Type parameter must be an unsigned integer literal");
        }
        if (bits >= Integer.SIZE) {
            throw validationEx(NOT_WELL_FORMED + "Type parameter exceeded maximum value");
        }
    }

    private void expectWord(String word) {
        if (!isWord(advance(), word)) {
            throw validationEx(NOT_WELL_FORMED + "Expected " + word + " keyword");
        }
    }

    private boolean isWord(Token token, String word) {
        return word.equalsIgnoreCase(token.value()) && isBareWord(token);
    }

    private boolean isBareWord(Token token) {
        return switch (token.type()) {
            case NULL, MISSING -> true;
            case IDENT -> statement.charAt(token.start()) != '"';
            default -> false;
        };
    }

    private boolean isStar(Token token) {
        return token.type() == TType.IDENT && "*".equals(token.value()) && statement.charAt(token.start()) == '*';
    }

    private void requireTermStart() {
        String unexpected = unexpectedTermStart();
        if (unexpected != null) {
            throw validationEx(NOT_WELL_FORMED + unexpected);
        }
    }

    private static final Set<TType> UNARY_OPERATORS = EnumSet.of(TType.PLUS, TType.MINUS, TType.NOT);

    // DML keywords are left out. DynamoDB reads one as the start of a nested statement.
    private String unexpectedTermStart() {
        int next = pos;
        while (UNARY_OPERATORS.contains(tokens.get(next).type())) {
            next++;
        }
        Token t = tokens.get(next);
        return switch (t.type()) {
            case EOF, SEMICOLON, RPAREN, RBRACKET, RBRACE, RBAG, COMMA, COLON, DOT, BY, ASC, DESC -> "Unexpected term";
            case WHERE, ORDER, VALUE, INTO, RETURNING, ALL, MODIFIED, OLD, NEW -> "Unexpected keyword";
            case EQ, NE, LT, LE, GT, GE, AND, OR, IS, IN, BETWEEN -> "Unexpected operator";
            case IDENT -> isStar(t) ? "Unexpected term" : isWord(t, "LIKE") ? "Unexpected operator" : null;
            default -> null;
        };
    }

    private void requireRightHandSide() {
        if (peek().type() == TType.EOF) {
            throw validationEx(NOT_WELL_FORMED + "Missing right-hand side expression of infix operator");
        }
    }

    private void requireSimplePathStart() {
        Token t = peek();
        if (t.type() != TType.IDENT || isStar(t) || isWord(t, "LIKE")) {
            throw validationEx(NOT_WELL_FORMED + "Expected identifier for simple path");
        }
    }

    private static final Set<String> SET_TYPES = Set.of("SS", "NS", "BS");

    private static final List<String> ATTRIBUTE_TYPE_NAMES =
            List.of("N", "BS", "L", "B", "NULL", "M", "S", "SS", "NS", "BOOL");

    private void requireAttributeTypeName(PVal type) {
        if (literalErrorPending()) {
            return;
        }
        if (!(type instanceof PVal.Str name)) {
            throw incorrectOperandType("attribute_type", type);
        }
        if (!ATTRIBUTE_TYPE_NAMES.contains(name.v())) {
            throw validationEx("Invalid attribute type name found; type: " + name.v()
                    + ", valid types: {" + String.join(",", ATTRIBUTE_TYPE_NAMES) + "}");
        }
    }

    private String parseOp() {
        Token t = advance();
        return switch (t.type()) {
            case EQ -> "=";   case NE -> "<>";
            case LT -> "<";   case LE -> "<=";
            case GT -> ">";   case GE -> ">=";
            default -> throw validationEx("Expected comparison operator, got: " + t.value());
        };
    }

    private SetClause parseAssign() {
        Path path = parseSimplePath();
        consume(TType.EQ);
        if (peekFunction("set_add") || peekFunction("set_delete")) {
            return parseSetMutation(path);
        }
        Operand left = parseOperand();
        if (peek().type() == TType.PLUS || peek().type() == TType.MINUS) {
            String op = advance().value();
            requireRightHandSide();
            Operand right = parseOperand();
            requireNumberLiteral(op, left);
            requireNumberLiteral(op, right);
            return new Assign(path, left, op, right);
        }
        return new Assign(path, left, null, null);
    }

    private void requireNumberLiteral(String op, Operand operand) {
        if (!literalErrorPending() && operand instanceof Operand.Value value && !(value.val() instanceof PVal.Num)) {
            throw incorrectOperandType(op, value.val());
        }
    }

    private SetClause parseSetMutation(Path target) {
        Token function = advance();
        String name = function.value().toUpperCase(Locale.ROOT);
        consume(TType.LPAREN);
        Token firstArgument = peek();
        if (!parsePath().equals(target) && !literalErrorPending()) {
            throw validationEx("The first argument to " + name + " must equal the assignment value at "
                    + position(function));
        }
        consume(TType.COMMA);
        PVal bag = parseValue();
        consume(TType.RPAREN);
        if (!SET_TYPES.contains(typeCode(bag)) && !literalErrorPending()) {
            throw validationEx("The second argument to " + name + " must be a value with type SET at "
                    + position(firstArgument));
        }
        return "set_add".equalsIgnoreCase(function.value()) ? new SetAdd(target, bag) : new SetDelete(target, bag);
    }

    private String position(Token token) {
        boolean quoted = statement.charAt(token.start()) == '"';
        return position(token.start(), token.value().length() + (quoted ? 2 : 0));
    }

    private String position(int start, int length) {
        int lineStart = statement.lastIndexOf('\n', start - 1) + 1;
        long line = statement.substring(0, start).chars().filter(c -> c == '\n').count() + 1;
        return line + ":" + (start - lineStart + 1) + ":" + length;
    }

    private Operand parseOperand() {
        if (peekFunction("list_append")) {
            advance();
            consume(TType.LPAREN);
            Operand first = parseOperand();
            consume(TType.COMMA);
            Operand second = parseOperand();
            consume(TType.RPAREN);
            return new Operand.ListAppend(first, second);
        }
        if (peek().type() == TType.IDENT) {
            return new Operand.Attribute(parsePath());
        }
        return new Operand.Value(parseValue());
    }

    private Path parseSimplePath() {
        requireSimplePathStart();
        return parsePath();
    }

    private Path parsePath() {
        requireTermStart();
        List<Seg> segments = new ArrayList<>();
        segments.add(new Seg.Name(expectIdent()));
        while (true) {
            switch (peek().type()) {
                case DOT -> {
                    advance();
                    segments.add(new Seg.Name(expectIdent()));
                }
                case LBRACKET -> {
                    advance();
                    segments.add(new Seg.Index(expectListIndex()));
                    consume(TType.RBRACKET);
                }
                default -> {
                    return new Path(List.copyOf(segments));
                }
            }
        }
    }

    private long expectListIndex() {
        Token t = advance();
        if (t.type() == TType.NUMBER) {
            try {
                long index = Long.parseLong(t.value());
                if (index < 0 || index > Integer.MAX_VALUE) {
                    int sign = index < 0 ? 1 : 0;
                    throw validationEx("List index is not within the allowable range; index: [" + t.value() + "] at "
                            + position(t.start() + sign, t.value().length() - sign));
                }
                return index;
            } catch (NumberFormatException expected) {
                // Falls through to the shared rejection below.
            }
        }
        throw validationEx("Expected a list index, got: '" + t.value() + "'");
    }

    private PVal parseValue() {
        return parseValue(null);
    }

    // Reads a literal with a stack of its open collections instead of recursion, so a deep literal
    // cannot exhaust the Java stack.
    private PVal parseValue(String literalPath) {
        Deque<OpenCollection> open = new ArrayDeque<>();
        String path = literalPath;
        while (true) {
            if (open.size() == DynamoDbAttributeValueValidator.MAX_NESTING_LEVELS) {
                defer(DynamoDbAttributeValueValidator.NESTING_EXCEEDED + " under " + path);
            }
            requireTermStart();
            Token t = advance();
            String nested = path == null ? "root" : path;
            OpenCollection parent = open.peek();
            PVal value = switch (t.type()) {
                case STRING   -> new PVal.Str(t.value());
                case NUMBER   -> checkedNumber(new PVal.Num(t.value()), nested);
                case BOOL     -> new PVal.Bool(Boolean.parseBoolean(t.value()));
                case NULL     -> new PVal.Null();
                case QUESTION -> {
                    if (path != null) {
                        defer("Unsupported data type: Parameter under key " + path + " at "
                                + position(t.start(), 1));
                    }
                    yield resolveParam();
                }
                case LBRACKET, LBRACE, LBAG -> {
                    open.push(new OpenCollection(t.type(), nested));
                    yield null;
                }
                case PLUS, MINUS -> checkedNumber(parseSignedNumber(t), nested);
                default -> throw validationEx("Expected value literal or ?, got: " + t.value());
            };
            if (parent != null && parent.close == TType.RBAG) {
                checkBagMember(parent, value);
            }
            while (true) {
                OpenCollection top = open.peek();
                if (top == null) {
                    return value;
                }
                if (value != null) {
                    top.add(value);
                    if (peek().type() == TType.COMMA) {
                        advance();
                    }
                }
                if (peek().type() == top.close) {
                    advance();
                    open.pop();
                    value = closed(top);
                    continue;
                }
                path = nextPath(top, open.size());
                break;
            }
        }
    }

    private static final class OpenCollection {
        private final TType close;
        private final String path;
        private final List<PVal> items = new ArrayList<>();
        private final Map<String, PVal> fields = new LinkedHashMap<>();
        private String key;
        private String memberType;
        private boolean invalid;

        OpenCollection(TType open, String path) {
            this.close = switch (open) {
                case LBRACKET -> TType.RBRACKET;
                case LBRACE -> TType.RBRACE;
                default -> TType.RBAG;
            };
            this.path = path;
        }

        void add(PVal value) {
            if (close == TType.RBRACE) {
                fields.put(key, value);
            } else {
                items.add(value);
            }
        }
    }

    // No message names a path below the deepest level DynamoDB allows, so paths stop growing there.
    private String nextPath(OpenCollection collection, int depth) {
        String step = switch (collection.close) {
            case RBRACE -> {
                collection.key = parseFieldName();
                yield "." + collection.key;
            }
            case RBRACKET -> "[" + collection.items.size() + "]";
            default -> "";
        };
        return depth > DynamoDbAttributeValueValidator.MAX_NESTING_LEVELS ? collection.path : collection.path + step;
    }

    private PVal closed(OpenCollection collection) {
        return switch (collection.close) {
            case RBRACKET -> new PVal.ListOf(List.copyOf(collection.items));
            case RBRACE -> new PVal.Tuple(collection.fields);
            default -> closedBag(collection);
        };
    }

    private String parseFieldName() {
        requireTermStart();
        String key = expectStringOrIdent();
        consume(TType.COLON);
        return key;
    }

    private static final MathContext DECIMAL_LITERAL = new MathContext(38, RoundingMode.HALF_EVEN);

    // DynamoDB rounds a literal written with a point or an exponent to 38 digits, and counts the
    // digits of one written without them.
    private PVal.Num checkedNumber(PVal.Num number, String literalPath) {
        if (deferredError != null) {
            return number;
        }
        BigDecimal value = new BigDecimal(number.v());
        boolean decimal = number.v().chars().anyMatch(c -> c == '.' || c == 'e' || c == 'E');
        if (decimal && DECIMAL_LITERAL.getPrecision() < value.precision()) {
            value = value.round(DECIMAL_LITERAL);
            number = new PVal.Num(value.toString());
        }
        String problem = numberLiteralProblem(value);
        if (problem != null) {
            defer(problem + " under " + literalPath);
        }
        return number;
    }

    // DynamoDB measures a literal in 32-bit arithmetic, so an exponent near the limit wraps around
    // and reads as an underflow.
    private static String numberLiteralProblem(BigDecimal number) {
        int magnitude = number.precision() - number.scale() + 1;
        if (127 < magnitude) {
            return DynamoDbNumberUtils.NUMBER_OVERFLOW;
        }
        if (magnitude < -128) {
            return DynamoDbNumberUtils.NUMBER_UNDERFLOW;
        }
        if (38 < number.stripTrailingZeros().precision()) {
            return DynamoDbNumberUtils.TOO_MANY_DIGITS;
        }
        return null;
    }

    private PVal.Num parseSignedNumber(Token sign) {
        Token operand = advance();
        PVal.Num number = switch (operand.type()) {
            case NUMBER -> new PVal.Num(operand.value());
            case PLUS, MINUS -> parseSignedNumber(operand);
            default -> throw validationEx("Unsupported operator in Condition Expression. Operator: " + sign.value());
        };
        if (sign.type() == TType.PLUS) {
            return number;
        }
        return new PVal.Num(number.v().startsWith("-") ? number.v().substring(1) : "-" + number.v());
    }

    // Stands in for a bag DynamoDB refuses, so the statement can be read to its end.
    private static final PVal.Bag REFUSED_BAG = new PVal.Bag("SS", List.of());

    // A collection member is refused before DynamoDB looks inside it.
    private void checkBagMember(OpenCollection bag, PVal member) {
        String type = member == null ? null : typeCode(member);
        if (bag.items.isEmpty() && ("S".equals(type) || "N".equals(type))) {
            bag.memberType = type;
        }
        if (type == null || !type.equals(bag.memberType)) {
            bag.invalid = true;
            defer("Unsupported data type in Bag. DynamoDB only supports either numbers or strings in bags");
        }
    }

    private PVal closedBag(OpenCollection bag) {
        if (bag.items.isEmpty()) {
            defer("Empty bags are not supported");
            return REFUSED_BAG;
        }
        if (bag.invalid) {
            return REFUSED_BAG;
        }
        if (!literalErrorPending()) {
            checkDistinctMembers(bag.memberType, bag.items);
        }
        return new PVal.Bag(bag.memberType + "S", List.copyOf(bag.items));
    }

    // DynamoDB checks operand types only after every literal converts, so a literal error wins.
    private boolean literalErrorPending() {
        return deferredError != null;
    }

    private void defer(String message) {
        if (deferredError == null) {
            deferredError = message;
        }
    }

    private void checkDistinctMembers(String memberType, List<PVal> members) {
        boolean numbers = memberType.equals("N");
        List<String> texts = members.stream()
                .map(member -> member instanceof PVal.Num n ? n.v() : ((PVal.Str) member).v())
                .toList();
        long distinct = texts.stream()
                .map(text -> numbers ? DynamoDbNumberUtils.validateAndNormalize(text) : text)
                .distinct()
                .count();
        if (distinct < texts.size()) {
            defer("One or more parameter values were invalid: Input collection ["
                    + String.join(", ", texts) + "] contains duplicates" + (numbers ? "! under root" : "."));
        }
    }

    private PVal resolveParam() {
        if (paramIdx >= parameters.size()) {
            throw validationEx("Not enough parameters supplied for ? placeholders");
        }
        JsonNode p = parameters.get(paramIdx++);
        String type = DynamoDbAttributeValueValidator.typeOf(p);
        return switch (type) {
            case "S"    -> new PVal.Str(p.get("S").asText());
            case "N"    -> new PVal.Num(p.get("N").asText());
            case "BOOL" -> new PVal.Bool(p.get("BOOL").asBoolean());
            case "NULL" -> new PVal.Null();
            default     -> new PVal.Av(type, p);
        };
    }

    private String expectIdent() {
        Token t = advance();
        if (t.type() != TType.IDENT) throw validationEx("Expected identifier, got: '" + t.value() + "'");
        return t.value();
    }

    private String expectStringOrIdent() {
        Token t = peek();
        if (t.type() == TType.STRING || t.type() == TType.IDENT) { advance(); return t.value(); }
        throw validationEx("Expected attribute name, got: '" + t.value() + "'");
    }

    private void consume(TType type) {
        Token t = advance();
        if (t.type() != type) throw validationEx("Expected " + type + ", got: '" + t.value() + "'");
    }

    private Token peek() { return tokens.get(pos); }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != TType.EOF) pos++;
        return t;
    }

    static AwsException validationEx(String msg) {
        return new AwsException("ValidationException", msg, 400);
    }
}
