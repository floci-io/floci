package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.*;

class DynamoDbPartiQLParser {

    enum TType {
        SELECT, FROM, WHERE, INSERT, INTO, VALUE,
        UPDATE, SET, REMOVE, DELETE, AND, BETWEEN,
        IDENT, STRING, NUMBER, BOOL, NULL, QUESTION,
        EQ, NE, LT, LE, GT, GE,
        LPAREN, RPAREN, LBRACE, RBRACE, COMMA, COLON,
        EOF
    }

    record Token(TType type, String value) {}

    // --- AST node types ---

    sealed interface Stmt permits Stmt.Select, Stmt.Insert, Stmt.Update, Stmt.Delete {
        record Select(String table, List<String> columns, List<Cond> where) implements Stmt {}
        record Insert(String table, Map<String, PVal> item)                 implements Stmt {}
        record Update(String table, List<Assign> sets, List<String> removes, List<Cond> where) implements Stmt {}
        record Delete(String table, List<Cond> where)                       implements Stmt {}
    }

    sealed interface PVal permits PVal.Str, PVal.Num, PVal.Bool, PVal.Null {
        record Str(String v)       implements PVal {}
        record Num(String v)       implements PVal {}
        record Bool(boolean v)     implements PVal {}
        record Null()              implements PVal {}
    }

    sealed interface Cond permits Cond.Eq, Cond.Cmp, Cond.Between, Cond.BeginsWith {
        record Eq(String attr, PVal val)                        implements Cond {}
        record Cmp(String attr, String op, PVal val)            implements Cond {}
        record Between(String attr, PVal lo, PVal hi)           implements Cond {}
        record BeginsWith(String attr, PVal prefix)             implements Cond {}
    }

    record Assign(String attr, PVal val) {}

    // --- Tokenizer ---

    static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0, n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '\'') {
                int start = ++i;
                while (i < n && input.charAt(i) != '\'') i++;
                tokens.add(new Token(TType.STRING, input.substring(start, i)));
                i++;
                continue;
            }
            if (c == '"') {
                int start = ++i;
                while (i < n && input.charAt(i) != '"') i++;
                tokens.add(new Token(TType.IDENT, input.substring(start, i)));
                i++;
                continue;
            }
            if (c == '?') { tokens.add(new Token(TType.QUESTION, "?")); i++; continue; }
            if (c == '*') { tokens.add(new Token(TType.IDENT, "*")); i++; continue; }
            if (c == '=') { tokens.add(new Token(TType.EQ, "=")); i++; continue; }
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
            if (c == ',') { tokens.add(new Token(TType.COMMA, ",")); i++; continue; }
            if (c == ':') { tokens.add(new Token(TType.COLON, ":")); i++; continue; }
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(input.charAt(i + 1)))) {
                int start = i;
                if (c == '-') i++;
                while (i < n && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) i++;
                tokens.add(new Token(TType.NUMBER, input.substring(start, i)));
                continue;
            }
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
                    case "BETWEEN"       -> TType.BETWEEN;
                    case "TRUE", "FALSE" -> TType.BOOL;
                    case "NULL"          -> TType.NULL;
                    default              -> TType.IDENT;
                };
                tokens.add(new Token(type, word));
                continue;
            }
            throw validationEx("Unexpected character '" + c + "' in PartiQL statement");
        }
        tokens.add(new Token(TType.EOF, ""));
        return tokens;
    }

    // --- Recursive-descent parser ---

    private final List<Token> tokens;
    private final List<JsonNode> parameters;
    private int pos = 0;
    private int paramIdx = 0;

    private DynamoDbPartiQLParser(List<Token> tokens, List<JsonNode> parameters) {
        this.tokens = tokens;
        this.parameters = parameters;
    }

    static Stmt parse(String statement, List<JsonNode> parameters) {
        return new DynamoDbPartiQLParser(tokenize(statement.trim()), parameters).parseStmt();
    }

    private Stmt parseStmt() {
        return switch (peek().type()) {
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            default -> throw validationEx("Unsupported PartiQL statement: " + peek().value());
        };
    }

    // SELECT col [, col …] | * FROM "Table" [WHERE cond [AND cond …]]
    private Stmt.Select parseSelect() {
        consume(TType.SELECT);
        List<String> cols = new ArrayList<>();
        if (peek().type() == TType.IDENT && "*".equals(peek().value())) {
            advance();
        } else {
            cols.add(expectIdent());
            while (peek().type() == TType.COMMA) { advance(); cols.add(expectIdent()); }
        }
        consume(TType.FROM);
        String table = expectIdent();
        List<Cond> where = new ArrayList<>();
        if (peek().type() == TType.WHERE) { advance(); where = parseConditions(); }
        return new Stmt.Select(table, cols, where);
    }

    // INSERT INTO "Table" VALUE {'key': val, …}
    private Stmt.Insert parseInsert() {
        consume(TType.INSERT);
        consume(TType.INTO);
        String table = expectIdent();
        consume(TType.VALUE);
        consume(TType.LBRACE);
        Map<String, PVal> item = new LinkedHashMap<>();
        while (peek().type() != TType.RBRACE && peek().type() != TType.EOF) {
            String key = expectStringOrIdent();
            consume(TType.COLON);
            item.put(key, parseValue());
            if (peek().type() == TType.COMMA) advance();
        }
        consume(TType.RBRACE);
        return new Stmt.Insert(table, item);
    }

    // UPDATE "Table" SET attr=val [, …] [REMOVE attr [, …]] WHERE …
    private Stmt.Update parseUpdate() {
        consume(TType.UPDATE);
        String table = expectIdent();
        List<Assign> sets = new ArrayList<>();
        List<String> removes = new ArrayList<>();
        while (peek().type() == TType.SET || peek().type() == TType.REMOVE) {
            if (peek().type() == TType.SET) {
                advance();
                sets.add(parseAssign());
                while (peek().type() == TType.COMMA) { advance(); sets.add(parseAssign()); }
            } else {
                advance();
                removes.add(expectIdent());
                while (peek().type() == TType.COMMA) { advance(); removes.add(expectIdent()); }
            }
        }
        consume(TType.WHERE);
        return new Stmt.Update(table, sets, removes, parseConditions());
    }

    // DELETE FROM "Table" WHERE …
    private Stmt.Delete parseDelete() {
        consume(TType.DELETE);
        consume(TType.FROM);
        String table = expectIdent();
        consume(TType.WHERE);
        return new Stmt.Delete(table, parseConditions());
    }

    private List<Cond> parseConditions() {
        List<Cond> conds = new ArrayList<>();
        conds.add(parseCond());
        while (peek().type() == TType.AND) { advance(); conds.add(parseCond()); }
        return conds;
    }

    private Cond parseCond() {
        if (peek().type() == TType.IDENT && "begins_with".equalsIgnoreCase(peek().value())) {
            advance();
            consume(TType.LPAREN);
            String attr = expectIdent();
            consume(TType.COMMA);
            PVal prefix = parseValue();
            consume(TType.RPAREN);
            return new Cond.BeginsWith(attr, prefix);
        }
        String attr = expectIdent();
        if (peek().type() == TType.BETWEEN) {
            advance();
            PVal lo = parseValue();
            consume(TType.AND);
            PVal hi = parseValue();
            return new Cond.Between(attr, lo, hi);
        }
        String op = parseOp();
        PVal val = parseValue();
        return "=".equals(op) ? new Cond.Eq(attr, val) : new Cond.Cmp(attr, op, val);
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

    private Assign parseAssign() {
        String attr = expectIdent();
        consume(TType.EQ);
        return new Assign(attr, parseValue());
    }

    private PVal parseValue() {
        Token t = advance();
        return switch (t.type()) {
            case STRING   -> new PVal.Str(t.value());
            case NUMBER   -> new PVal.Num(t.value());
            case BOOL     -> new PVal.Bool(Boolean.parseBoolean(t.value()));
            case NULL     -> new PVal.Null();
            case QUESTION -> resolveParam();
            default -> throw validationEx("Expected value literal or ?, got: " + t.value());
        };
    }

    private PVal resolveParam() {
        if (paramIdx >= parameters.size()) {
            throw validationEx("Not enough parameters supplied for ? placeholders");
        }
        JsonNode p = parameters.get(paramIdx++);
        if (p.has("S"))    return new PVal.Str(p.get("S").asText());
        if (p.has("N"))    return new PVal.Num(p.get("N").asText());
        if (p.has("BOOL")) return new PVal.Bool(p.get("BOOL").asBoolean());
        if (p.has("NULL")) return new PVal.Null();
        throw validationEx("Unsupported parameter type in parameters array");
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
