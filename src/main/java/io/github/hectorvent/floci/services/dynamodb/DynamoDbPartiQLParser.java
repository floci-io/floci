package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.*;

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

    // start is the offset in the statement, kept only where an error message quotes it.
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

        // PartiQL reports a document path from its last named component on, list indexes
        // included, so SELECT a.b[0] answers b[0] (checked on real AWS, eu-west-2, 2026-09-17).
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

    /**
     * A RETURNING clause, named after the PartiQL words so {@link #clause()} can quote
     * the statement back. The classic ReturnValues parameter calls the same MODIFIED
     * projection UPDATED, which is what {@link #returnValues()} answers.
     */
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
        record Select(String table, String index, List<Path> columns, List<Cond> where,
                      List<OrderTerm> orderBy)                              implements Stmt {}
        record Insert(String table, Map<String, PVal> item)                 implements Stmt {}
        record Update(String table, String index, List<SetClause> sets, List<Path> removes,
                      List<Cond> where, Returning returning)                implements Stmt {}
        record Delete(String table, String index, List<Cond> where, Returning returning) implements Stmt {}
        record Exists(Select select) implements Stmt {
            @Override
            public String table() {
                return select.table();
            }
        }
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
        // A string or number set, written <<...>>.
        record Bag(String type, List<PVal> members) implements PVal {}
    }

    sealed interface Cond permits Cond.Leaf, Cond.And, Cond.Or, Cond.Not {

        /** The attribute the condition names outright. A nested path or a composite names none. */
        default Optional<String> bareAttribute() {
            return Optional.empty();
        }

        /** A condition reading one attribute. The composites below reach several. */
        sealed interface Leaf extends Cond permits Eq, Cmp, Between, BeginsWith, In, Missing,
                Contains, AttributeType, SizeCmp, IsNull {
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
        record IsNull(Path path, boolean negated)               implements Leaf {}
        record And(List<Cond> operands)                         implements Cond {}
        record Or(List<Cond> operands)                          implements Cond {}
        record Not(Cond operand)                                implements Cond {}
    }

    sealed interface Operand permits Operand.Value, Operand.Attribute, Operand.ListAppend {
        record Value(PVal val)      implements Operand {}
        record Attribute(Path path) implements Operand {}
        record ListAppend(Operand first, Operand second) implements Operand {}
    }

    sealed interface SetClause permits Assign, SetAdd, SetDelete {
        Path path();
    }

    // op is null for a plain assignment, + or - for arithmetic.
    record Assign(Path path, Operand left, String op, Operand right) implements SetClause {}
    record SetAdd(Path path, PVal bag)                               implements SetClause {}
    record SetDelete(Path path, PVal bag)                            implements SetClause {}

    // --- Tokenizer ---

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
                    throw validationEx("Statement wasn't well formed, can't be processed: ");
                }
                i = end + 2;
                continue;
            }
            if (c == '\'') {
                StringBuilder text = new StringBuilder();
                i++;
                // Two single quotes in a row stand for one.
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
            if (c == '*') { tokens.add(new Token(TType.IDENT, "*")); i++; continue; }
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
            if (c == '.') { tokens.add(new Token(TType.DOT, ".")); i++; continue; }
            // A minus right after an operand subtracts, so n-1 is n minus 1 and not n followed by -1.
            if (Character.isDigit(c)
                    || (c == '-' && i + 1 < n && Character.isDigit(input.charAt(i + 1)) && !endsOperand(tokens))) {
                int start = i;
                if (c == '-') i++;
                while (i < n && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) i++;
                i = skipExponent(input, i);
                tokens.add(new Token(TType.NUMBER, input.substring(start, i), start));
                continue;
            }
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

    // --- Recursive-descent parser ---

    private final String statement;
    private final List<Token> tokens;
    private final List<JsonNode> parameters;
    private int pos = 0;
    private int paramIdx = 0;
    private int literalDepth = 0;

    private DynamoDbPartiQLParser(String statement, List<JsonNode> parameters) {
        this.statement = statement;
        this.tokens = tokenize(statement);
        this.parameters = parameters;
    }

    static Stmt parse(String statement, List<JsonNode> parameters) {
        parameters.forEach(DynamoDbAttributeValueValidator::validate);
        parameters.forEach(DynamoDbAttributeValueValidator::requireParameterNestingWithinLimit);
        DynamoDbPartiQLParser parser = new DynamoDbPartiQLParser(statement, parameters);
        Stmt stmt = parser.parseStmt();
        if (parser.peek().type() == TType.SEMICOLON) {
            parser.advance();
        }
        if (parser.peek().type() != TType.EOF) {
            throw validationEx("Statement wasn't well formed, can't be processed: Unexpected token after expression");
        }
        return stmt;
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
            Stmt.Select select = parseSelect();
            consume(TType.RPAREN);
            return new Stmt.Exists(select);
        }
        return switch (peek().type()) {
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            default -> throw validationEx(
                    "Statement wasn't well formed, can't be processed: Expected data manipulation");
        };
    }

    // SELECT col [, col …] | * FROM "Table"["." "Index"] [WHERE cond [AND cond …]]
    private Stmt.Select parseSelect() {
        consume(TType.SELECT);
        List<Path> cols = new ArrayList<>();
        if (peek().type() == TType.IDENT && "*".equals(peek().value())) {
            advance();
        } else {
            cols.add(parsePath());
            while (peek().type() == TType.COMMA) { advance(); cols.add(parsePath()); }
        }
        consume(TType.FROM);
        String table = expectTableName();
        String index = parseIndexQualifier();
        List<Cond> where = new ArrayList<>();
        if (peek().type() == TType.WHERE) { advance(); where = parseConditions(); }
        return new Stmt.Select(table, index, cols, where, parseOrderBy());
    }

    // ORDER BY key [ASC | DESC] [, …]
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
        String table = expectTableName();
        // An index qualifier is ungrammatical here, unlike on UPDATE and DELETE.
        if (peek().type() == TType.DOT) {
            throw validationEx("FROM clause may only contain a single table name");
        }
        consume(TType.VALUE);
        consume(TType.LBRACE);
        return new Stmt.Insert(table, parseTupleFields(null));
    }

    // Called with the opening brace already consumed.
    private Map<String, PVal> parseTupleFields(String literalPath) {
        boolean literal = literalPath != null;
        literalDepth += literal ? 1 : 0;
        Map<String, PVal> fields = new LinkedHashMap<>();
        while (peek().type() != TType.RBRACE && peek().type() != TType.EOF) {
            String key = expectStringOrIdent();
            consume(TType.COLON);
            fields.put(key, parseValue(literal ? literalPath + "." + key : null));
            if (peek().type() == TType.COMMA) {
                advance();
            }
        }
        consume(TType.RBRACE);
        literalDepth -= literal ? 1 : 0;
        return fields;
    }

    // UPDATE "Table" SET attr=val [, …] [REMOVE attr [, …]] WHERE …
    private Stmt.Update parseUpdate() {
        consume(TType.UPDATE);
        String table = expectTableName();
        String index = parseIndexQualifier();
        List<SetClause> sets = new ArrayList<>();
        List<Path> removes = new ArrayList<>();
        while (peek().type() == TType.SET || peek().type() == TType.REMOVE) {
            if (peek().type() == TType.SET) {
                advance();
                sets.add(parseAssign());
                while (peek().type() == TType.COMMA) { advance(); sets.add(parseAssign()); }
            } else {
                advance();
                removes.add(parsePath());
                while (peek().type() == TType.COMMA) { advance(); removes.add(parsePath()); }
            }
        }
        consume(TType.WHERE);
        List<Cond> where = parseConditions();
        return new Stmt.Update(table, index, sets, removes, where, parseReturning());
    }

    // DELETE FROM "Table" WHERE … [RETURNING ALL OLD *]
    private Stmt.Delete parseDelete() {
        consume(TType.DELETE);
        consume(TType.FROM);
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

    // RETURNING (ALL | MODIFIED) (OLD | NEW) *
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

    /** The top-level AND operands, which is where a key equality can appear. */
    private List<Cond> parseConditions() {
        Cond cond = parseOr();
        return cond instanceof Cond.And and ? and.operands() : List.of(cond);
    }

    private Cond parseOr() {
        List<Cond> operands = new ArrayList<>();
        operands.add(parseAnd());
        while (peek().type() == TType.OR) {
            advance();
            operands.add(parseAnd());
        }
        return operands.size() == 1 ? operands.getFirst() : new Cond.Or(List.copyOf(operands));
    }

    // A parenthesised AND inside an AND is spliced in, so (pk = 'a' AND sk = 'b') AND x = 1
    // still shows its key equalities at the top level.
    private Cond parseAnd() {
        List<Cond> operands = new ArrayList<>();
        addAndOperand(operands, parseNot());
        while (peek().type() == TType.AND) {
            advance();
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

    // A condition compared with a value reads as a boolean, so attribute_exists(a) = false holds
    // for a missing a and attribute_exists(a) = 1 holds for nothing (checked on real AWS, eu-west-2, 2026-09-17).
    private Cond parseComparison() {
        TType afterBool = tokens.get(Math.min(pos + 1, tokens.size() - 1)).type();
        if (peek().type() == TType.BOOL && (afterBool == TType.EQ || afterBool == TType.NE)) {
            boolean flag = Boolean.parseBoolean(advance().value());
            boolean equal = advance().type() == TType.EQ;
            Cond predicate = parseCond();
            return flag == equal ? predicate : new Cond.Not(predicate);
        }
        Cond cond = parseCond();
        if (peek().type() == TType.BETWEEN) {
            throw incorrectOperandType("BETWEEN", new PVal.Bool(true));
        }
        if (peek().type() == TType.IN) {
            advance();
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
        if (!"=".equals(op) && !"<>".equals(op)) {
            throw incorrectOperandType(op, new PVal.Bool(true));
        }
        Cond equal = conditionEquals(cond);
        return "=".equals(op) ? equal : new Cond.Not(equal);
    }

    private Cond conditionEquals(Cond cond) {
        boolean predicateNext = peek().type() == TType.LPAREN
                || PREDICATE_FUNCTIONS.stream().anyMatch(this::peekFunction);
        if (predicateNext) {
            Cond other = parseCond();
            return new Cond.Or(List.of(new Cond.And(List.of(cond, other)),
                    new Cond.And(List.of(new Cond.Not(cond), new Cond.Not(other)))));
        }
        if (peek().type() == TType.IDENT) {
            Path path = parsePath();
            return new Cond.Or(List.of(new Cond.And(List.of(cond, new Cond.Eq(path, new PVal.Bool(true)))),
                    new Cond.And(List.of(new Cond.Not(cond), new Cond.Eq(path, new PVal.Bool(false))))));
        }
        if (parseValue() instanceof PVal.Bool flag) {
            return flag.v() ? cond : new Cond.Not(cond);
        }
        return new Cond.And(List.of(cond, new Cond.Not(cond)));
    }

    // S, N and B are the only types DynamoDB gives an ordering.
    private static final Set<String> ORDERED_TYPES = Set.of("S", "N", "B");

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

    private static void requireOrdered(String op, PVal val) {
        if (!ORDERED_TYPES.contains(typeCode(val))) {
            throw incorrectOperandType(op, val);
        }
    }

    private static AwsException incorrectOperandType(String op, PVal val) {
        return validationEx("Incorrect operand type for operator or function; "
                + "operator or function: " + op + ", operand type: " + typeCode(val));
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
            PVal val = parseValue();
            if (!"=".equals(op) && !"<>".equals(op)) {
                requireOrdered(op, val);
            }
            return new Cond.SizeCmp(path, op, val);
        }
        Path path = parsePath();
        if (peek().type() == TType.BETWEEN) {
            advance();
            PVal lo = parseValue();
            consume(TType.AND);
            PVal hi = parseValue();
            requireOrdered("BETWEEN", lo);
            requireOrdered("BETWEEN", hi);
            return new Cond.Between(path, lo, hi);
        }
        if (peek().type() == TType.IN) {
            advance();
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
            if (peek().type() == TType.NULL) {
                advance();
                return new Cond.IsNull(path, negated);
            }
            consume(TType.MISSING);
            return new Cond.Missing(path, negated);
        }
        String op = parseOp();
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

    private static final Set<String> SET_TYPES = Set.of("SS", "NS", "BS");

    private static final List<String> ATTRIBUTE_TYPE_NAMES =
            List.of("N", "BS", "L", "B", "NULL", "M", "S", "SS", "NS", "BOOL");

    private static void requireAttributeTypeName(PVal type) {
        if (!(type instanceof PVal.Str)) {
            throw incorrectOperandType("attribute_type", type);
        }
        if (type instanceof PVal.Str name && !ATTRIBUTE_TYPE_NAMES.contains(name.v())) {
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
        Path path = parsePath();
        consume(TType.EQ);
        if (peekFunction("set_add") || peekFunction("set_delete")) {
            return parseSetMutation(path);
        }
        Operand left = parseOperand();
        if (peek().type() == TType.PLUS || peek().type() == TType.MINUS) {
            String op = advance().value();
            Operand right = parseOperand();
            requireNumberLiteral(op, left);
            requireNumberLiteral(op, right);
            return new Assign(path, left, op, right);
        }
        return new Assign(path, left, null, null);
    }

    private static void requireNumberLiteral(String op, Operand operand) {
        if (operand instanceof Operand.Value value && !(value.val() instanceof PVal.Num)) {
            throw incorrectOperandType(op, value.val());
        }
    }

    // SET path = set_add(path, <<...>>), where both paths must be the same one.
    private SetClause parseSetMutation(Path target) {
        Token function = advance();
        String name = function.value().toUpperCase(Locale.ROOT);
        consume(TType.LPAREN);
        Token firstArgument = peek();
        if (!parsePath().equals(target)) {
            throw validationEx("The first argument to " + name + " must equal the assignment value at "
                    + position(function));
        }
        consume(TType.COMMA);
        PVal bag = parseValue();
        consume(TType.RPAREN);
        if (!SET_TYPES.contains(typeCode(bag))) {
            throw validationEx("The second argument to " + name + " must be a value with type SET at "
                    + position(firstArgument));
        }
        return "set_add".equalsIgnoreCase(function.value()) ? new SetAdd(target, bag) : new SetDelete(target, bag);
    }

    // line:column:length, as AWS quotes a token back (checked on real AWS, eu-west-2, 2026-09-17).
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

    private Path parsePath() {
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
                if (index < 0) {
                    // AWS points past the minus sign at the digits (checked on real AWS, eu-west-2, 2026-09-17).
                    throw validationEx("List index is not within the allowable range; index: [" + t.value() + "] at "
                            + position(t.start() + 1, t.value().length() - 1));
                }
                if (index > Integer.MAX_VALUE) {
                    throw validationEx("List index is not within the allowable range; index: [" + t.value() + "] at "
                            + position(t.start(), t.value().length()));
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

    // AWS takes a parameter as an INSERT item field, but not inside a list, map or set literal
    // (checked on real AWS, eu-west-2, 2026-09-17).
    private PVal parseValue(String literalPath) {
        // A literal member 32 levels down is refused (checked on real AWS, eu-west-2, 2026-09-17).
        if (literalDepth == 32) {
            throw validationEx("Nesting Levels have exceeded supported limits under " + literalPath);
        }
        Token t = advance();
        String nested = literalPath == null ? "root" : literalPath;
        return switch (t.type()) {
            case STRING   -> new PVal.Str(t.value());
            case NUMBER   -> new PVal.Num(t.value());
            case BOOL     -> new PVal.Bool(Boolean.parseBoolean(t.value()));
            case NULL     -> new PVal.Null();
            case QUESTION -> {
                if (literalPath != null) {
                    throw validationEx("Unsupported data type: Parameter under key " + literalPath + " at "
                            + position(t.start(), 1));
                }
                yield resolveParam();
            }
            case LBRACKET -> new PVal.ListOf(parseListItems(nested));
            case LBRACE   -> new PVal.Tuple(parseTupleFields(nested));
            case LBAG     -> parseBag(nested);
            case PLUS, MINUS -> parseSignedNumber(t);
            default -> throw validationEx("Expected value literal or ?, got: " + t.value());
        };
    }

    // A sign applies only to a number literal, never to a parameter or a path.
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

    // Called with the opening bracket already consumed.
    private List<PVal> parseListItems(String literalPath) {
        literalDepth++;
        List<PVal> items = new ArrayList<>();
        while (peek().type() != TType.RBRACKET && peek().type() != TType.EOF) {
            items.add(parseValue(literalPath + "[" + items.size() + "]"));
            if (peek().type() == TType.COMMA) {
                advance();
            }
        }
        consume(TType.RBRACKET);
        literalDepth--;
        return List.copyOf(items);
    }

    // A bag holds strings or numbers, never both (checked on real AWS, eu-west-2, 2026-09-17).
    private PVal.Bag parseBag(String literalPath) {
        literalDepth++;
        List<PVal> members = new ArrayList<>();
        while (peek().type() != TType.RBAG && peek().type() != TType.EOF) {
            members.add(parseValue(literalPath));
            if (peek().type() == TType.COMMA) {
                advance();
            }
        }
        consume(TType.RBAG);
        literalDepth--;
        if (members.isEmpty()) {
            throw validationEx("Empty bags are not supported");
        }
        String memberType = typeCode(members.getFirst());
        boolean uniform = members.stream().allMatch(m -> typeCode(m).equals(memberType));
        if (!uniform || !(memberType.equals("S") || memberType.equals("N"))) {
            throw validationEx(
                    "Unsupported data type in Bag. DynamoDB only supports either numbers or strings in bags");
        }
        requireDistinctMembers(memberType, members);
        return new PVal.Bag(memberType + "S", List.copyOf(members));
    }

    private static void requireDistinctMembers(String memberType, List<PVal> members) {
        boolean numbers = memberType.equals("N");
        List<String> texts = members.stream()
                .map(member -> member instanceof PVal.Num n ? n.v() : ((PVal.Str) member).v())
                .toList();
        long distinct = texts.stream()
                .map(text -> numbers ? DynamoDbNumberUtils.validateAndNormalize(text) : text)
                .distinct()
                .count();
        if (distinct < texts.size()) {
            throw validationEx("One or more parameter values were invalid: Input collection ["
                    + String.join(", ", texts) + "] contains duplicates" + (numbers ? "! under root" : "."));
        }
    }

    private PVal resolveParam() {
        if (paramIdx >= parameters.size()) {
            throw validationEx("Not enough parameters supplied for ? placeholders");
        }
        JsonNode p = parameters.get(paramIdx++);
        var type = DynamoDbAttributeValueValidator.typeOf(p);
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
