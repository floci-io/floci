package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.glue.model.Column;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Works out, from the text of a query, which partitions of an external table the query can touch.
 *
 * <p>Spectrum reads only the partitions whose key values satisfy the partition predicates of the
 * {@code WHERE} clause. This class reads those predicates and nothing else: the original SQL still runs
 * against the loaded rows, so a partition kept here costs time and a partition dropped wrongly would lose
 * rows. It therefore drops a partition only when a predicate it fully understands rules it out, and keeps
 * every partition otherwise.
 *
 * <p>A predicate counts when it is a top-level {@code AND} term of the {@code WHERE} of the query block that
 * names the table, built only from the table's partition keys, literals, comparisons, {@code IN},
 * {@code BETWEEN}, {@code IS [NOT] NULL}, {@code AND}, {@code OR} and {@code NOT}, over integer, date and
 * string keys, and it rejects a row whose keys are all NULL. The last rule keeps the predicate valid when the
 * table is on the nullable side of an outer join, where an unmatched row has NULL keys.
 */
final class PartitionPruner {
    private static final String HIVE_DEFAULT_PARTITION = "__HIVE_DEFAULT_PARTITION__";
    private static final Set<String> SEGMENT_BOUNDARIES = Set.of("union", "intersect", "except");
    private static final Set<String> CLAUSE_AFTER_WHERE = Set.of("group", "order", "having", "limit", "offset",
            "fetch", "window", "qualify", "for", "returning");
    private static final Set<String> NOT_AN_ALIAS = Set.of("where", "join", "inner", "left", "right", "full",
            "cross", "natural", "on", "using", "group", "order", "having", "limit", "offset", "union", "intersect",
            "except", "window", "fetch", "for", "lateral", "tablesample", "qualify", "returning", "set", "values",
            "select", "from", "as");

    private PartitionPruner() {
    }

    /** The partitions of one table that a query can touch. */
    static final class Filter {
        private final List<Column> keys;
        private final List<List<Node>> occurrences;

        private Filter(List<Column> keys, List<List<Node>> occurrences) {
            this.keys = keys;
            this.occurrences = occurrences;
        }

        /** Whether a partition with these key values can hold rows the query reads. */
        boolean matches(List<String> values) {
            Row row = new Row(keys, values);
            for (List<Node> conjuncts : occurrences) {
                boolean all = true;
                for (Node conjunct : conjuncts) {
                    try {
                        if (conjunct.eval(row) != Tri.TRUE) {
                            all = false;
                            break;
                        }
                    } catch (Undecidable expected) {
                        // a key value this class cannot read stays in, as if the predicate did not exist
                    }
                }
                if (all) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The filter for {@code schema.table} in {@code sql}, or empty when every partition may be read: the
     * table has no usable predicate in some place it is named, or the text is not understood.
     */
    static Optional<Filter> filterFor(String sql, String schema, String table, List<Column> partitionKeys) {
        if (sql == null || partitionKeys == null || partitionKeys.isEmpty()) {
            return Optional.empty();
        }
        Tokens tokens = tokenize(sql);
        if (tokens.unsafe || !tokens.balanced()) {
            return Optional.empty();
        }
        List<List<Node>> occurrences = new ArrayList<>();
        int found = 0;
        for (int i = 0; i < tokens.list.size(); i++) {
            Chain chain = chainAt(tokens, i);
            if (chain == null) {
                continue;
            }
            if (isTable(chain, schema, table)) {
                found++;
                List<Node> conjuncts = conjunctsFor(tokens, chain, schema, table, partitionKeys);
                if (conjuncts.isEmpty()) {
                    return Optional.empty();
                }
                occurrences.add(conjuncts);
            }
            i = chain.end - 1;
        }
        return found == 0 ? Optional.empty() : Optional.of(new Filter(partitionKeys, occurrences));
    }

    private static List<Node> conjunctsFor(Tokens tokens, Chain occurrence, String schema, String table,
                                           List<Column> keys) {
        int depth = tokens.depth[occurrence.start];
        int blockStart = 0;
        for (int j = occurrence.start - 1; j >= 0; j--) {
            if (tokens.depth[j] < depth || tokens.depth[j] == depth && isBoundary(tokens.list.get(j))) {
                blockStart = j + 1;
                break;
            }
        }
        int blockEnd = tokens.list.size();
        for (int j = occurrence.end; j < tokens.list.size(); j++) {
            if (tokens.depth[j] < depth || tokens.depth[j] == depth && isBoundary(tokens.list.get(j))) {
                blockEnd = j;
                break;
            }
        }
        int where = -1;
        for (int j = blockStart; j < blockEnd; j++) {
            if (tokens.depth[j] == depth && tokens.list.get(j).isWord("where")) {
                where = j;
                break;
            }
        }
        if (where < occurrence.end) {
            return List.of();
        }
        int clauseEnd = blockEnd;
        int caseLevel = 0;
        for (int j = where + 1; j < blockEnd; j++) {
            if (tokens.depth[j] == depth) {
                Token token = tokens.list.get(j);
                if (token.isWord("case")) {
                    caseLevel++;
                } else if (token.isWord("end")) {
                    caseLevel = Math.max(0, caseLevel - 1);
                } else if (caseLevel == 0 && CLAUSE_AFTER_WHERE.stream().anyMatch(token::isWord)) {
                    clauseEnd = j;
                    break;
                }
            }
        }
        Set<List<String>> qualifiers = qualifiers(tokens, occurrence, schema, table);
        if (qualifiers == null) {
            return List.of();
        }
        List<Node> conjuncts = new ArrayList<>();
        for (List<Token> conjunct : split(tokens.list.subList(where + 1, clauseEnd))) {
            Node node = parseConjunct(conjunct, keys, qualifiers);
            if (node != null && rejectsNullKeys(node, keys)) {
                conjuncts.add(node);
            }
        }
        return conjuncts;
    }

    private static boolean rejectsNullKeys(Node node, List<Column> keys) {
        List<String> nulls = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            nulls.add(null);
        }
        try {
            return node.eval(new Row(keys, nulls)) != Tri.TRUE;
        } catch (Undecidable expected) {
            // an all-NULL row always evaluates, so this is only a defence: a predicate that cannot be checked is not used
            return false;
        }
    }

    /**
     * The qualifiers a column of this table may carry in the block, each as the words before the column name:
     * the alias when the table is aliased, else the table name, with its schema and with its database too.
     * Null when the alias cannot be read, which leaves only unqualified columns in the predicate.
     */
    private static Set<List<String>> qualifiers(Tokens tokens, Chain occurrence, String schema, String table) {
        int next = occurrence.end;
        String alias = null;
        if (next < tokens.list.size() && tokens.list.get(next).isWord("as")) {
            next++;
        }
        if (next < tokens.list.size()) {
            Token candidate = tokens.list.get(next);
            boolean named = candidate.kind == Kind.QUOTED_WORD
                    || candidate.kind == Kind.WORD && !NOT_AN_ALIAS.contains(candidate.text);
            if (named) {
                alias = candidate.text;
                if (next + 1 < tokens.list.size() && tokens.list.get(next + 1).kind == Kind.LPAREN) {
                    return null;
                }
            }
        }
        if (alias != null) {
            return Set.of(List.of(alias));
        }
        Set<List<String>> names = new HashSet<>();
        names.add(List.of(table));
        names.add(List.of(schema, table));
        if (occurrence.words.size() == 3) {
            names.add(occurrence.words.stream().map(Token::text).toList());
        }
        return names;
    }

    private static boolean isBoundary(Token token) {
        return token.kind == Kind.SEMICOLON || token.kind == Kind.WORD && SEGMENT_BOUNDARIES.contains(token.text);
    }

    /** Splits the terms of a {@code WHERE}; a clause holding a top-level OR is a single term. */
    private static List<List<Token>> split(List<Token> clause) {
        int depth = 0;
        int caseDepth = 0;
        for (Token token : clause) {
            if (token.kind == Kind.LPAREN) {
                depth++;
            } else if (token.kind == Kind.RPAREN) {
                depth--;
            } else if (token.isWord("case")) {
                caseDepth++;
            } else if (token.isWord("end")) {
                caseDepth = Math.max(0, caseDepth - 1);
            } else if (depth == 0 && caseDepth == 0 && token.isWord("or")) {
                return List.of(clause);
            }
        }
        if (caseDepth != 0) {
            return List.of();
        }
        List<List<Token>> terms = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        depth = 0;
        caseDepth = 0;
        boolean inBetween = false;
        for (Token token : clause) {
            if (token.kind == Kind.LPAREN) {
                depth++;
            } else if (token.kind == Kind.RPAREN) {
                depth--;
            } else if (token.isWord("case")) {
                caseDepth++;
            } else if (token.isWord("end")) {
                caseDepth = Math.max(0, caseDepth - 1);
            }
            if (depth == 0 && caseDepth == 0 && token.isWord("between")) {
                inBetween = true;
            }
            if (depth == 0 && caseDepth == 0 && token.isWord("and")) {
                if (inBetween) {
                    inBetween = false;
                } else {
                    terms.add(current);
                    current = new ArrayList<>();
                    continue;
                }
            }
            current.add(token);
        }
        terms.add(current);
        return terms;
    }

    // ---- predicate parsing and evaluation ----

    private enum Tri { TRUE, FALSE, UNKNOWN;
        Tri not() {
            return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
        }
    }

    /** A key value that cannot be compared the way PostgreSQL would compare it. */
    private static final class Undecidable extends RuntimeException {
        Undecidable() {
            super(null, null, false, false);
        }
    }

    /** A term this class does not support. */
    private static final class Unsupported extends RuntimeException {
        Unsupported() {
            super(null, null, false, false);
        }
    }

    private record Row(List<Column> keys, List<String> values) {
        String value(int index) {
            String value = index < values.size() ? values.get(index) : null;
            if (value == null || HIVE_DEFAULT_PARTITION.equals(value)
                    || GlueTypeMapper.exceedsWidth(keys.get(index).getType(), value)) {
                return null;
            }
            return value;
        }
    }

    private interface Node {
        Tri eval(Row row);
    }

    private enum KeyType { INTEGER, DATE, STRING }

    private static KeyType keyType(Column key) {
        String type = GlueTypeMapper.canonicalGlueType(key.getType());
        return switch (type) {
            case "tinyint", "smallint", "int", "integer", "bigint" -> KeyType.INTEGER;
            case "date" -> KeyType.DATE;
            case "string" -> KeyType.STRING;
            default -> type.matches("(?:varchar|char)\\(\\d+\\)") ? KeyType.STRING : null;
        };
    }

    private static Node parseConjunct(List<Token> tokens, List<Column> keys, Set<List<String>> qualifiers) {
        try {
            Parser parser = new Parser(tokens, keys, qualifiers);
            Node node = parser.expression();
            return parser.atEnd() ? node : null;
        } catch (Unsupported expected) {
            return null;
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final List<Column> keys;
        private final Set<List<String>> qualifiers;
        private int position;

        Parser(List<Token> tokens, List<Column> keys, Set<List<String>> qualifiers) {
            this.tokens = tokens;
            this.keys = keys;
            this.qualifiers = qualifiers;
        }

        boolean atEnd() {
            return position == tokens.size();
        }

        Node expression() {
            Node left = conjunction();
            while (word("or")) {
                position++;
                Node a = left;
                Node b = conjunction();
                left = row -> {
                    Tri x = a.eval(row);
                    Tri y = b.eval(row);
                    return x == Tri.TRUE || y == Tri.TRUE ? Tri.TRUE
                            : x == Tri.FALSE && y == Tri.FALSE ? Tri.FALSE : Tri.UNKNOWN;
                };
            }
            return left;
        }

        private Node conjunction() {
            Node left = negation();
            while (word("and")) {
                position++;
                Node a = left;
                Node b = negation();
                left = row -> {
                    Tri x = a.eval(row);
                    Tri y = b.eval(row);
                    return x == Tri.FALSE || y == Tri.FALSE ? Tri.FALSE
                            : x == Tri.TRUE && y == Tri.TRUE ? Tri.TRUE : Tri.UNKNOWN;
                };
            }
            return left;
        }

        private Node negation() {
            if (word("not")) {
                position++;
                Node inner = negation();
                return row -> inner.eval(row).not();
            }
            return predicate();
        }

        private Node predicate() {
            if (peekKind(Kind.LPAREN)) {
                position++;
                Node inner = expression();
                expect(Kind.RPAREN);
                return inner;
            }
            Operand left = operand();
            if (word("is")) {
                position++;
                boolean negated = word("not");
                if (negated) {
                    position++;
                }
                if (!word("null") || left.key < 0) {
                    throw new Unsupported();
                }
                position++;
                int index = left.key;
                return row -> (row.value(index) == null) != negated ? Tri.TRUE : Tri.FALSE;
            }
            boolean negated = word("not");
            if (negated) {
                position++;
            }
            if (word("in")) {
                position++;
                return negate(inList(left), negated);
            }
            if (word("between")) {
                position++;
                Operand low = operand();
                if (!word("and")) {
                    throw new Unsupported();
                }
                position++;
                Operand high = operand();
                Node ge = comparison(left, ">=", low);
                Node le = comparison(left, "<=", high);
                Node both = row -> {
                    Tri x = ge.eval(row);
                    Tri y = le.eval(row);
                    return x == Tri.FALSE || y == Tri.FALSE ? Tri.FALSE
                            : x == Tri.TRUE && y == Tri.TRUE ? Tri.TRUE : Tri.UNKNOWN;
                };
                return negate(both, negated);
            }
            if (negated || !peekKind(Kind.OP)) {
                throw new Unsupported();
            }
            String operator = tokens.get(position++).text;
            return comparison(left, operator, operand());
        }

        private static Node negate(Node node, boolean negated) {
            return negated ? row -> node.eval(row).not() : node;
        }

        private Node inList(Operand left) {
            expect(Kind.LPAREN);
            List<Node> equalities = new ArrayList<>();
            do {
                equalities.add(comparison(left, "=", operand()));
                if (peekKind(Kind.COMMA)) {
                    position++;
                } else {
                    break;
                }
            } while (true);
            expect(Kind.RPAREN);
            return row -> {
                boolean unknown = false;
                for (Node equality : equalities) {
                    Tri result = equality.eval(row);
                    if (result == Tri.TRUE) {
                        return Tri.TRUE;
                    }
                    unknown |= result == Tri.UNKNOWN;
                }
                return unknown ? Tri.UNKNOWN : Tri.FALSE;
            };
        }

        private Node comparison(Operand left, String operator, Operand right) {
            Operand key = left;
            Operand literal = right;
            String effective = operator;
            if (left.key < 0 && right.key >= 0) {
                key = right;
                literal = left;
                effective = switch (operator) {
                    case "<" -> ">";
                    case ">" -> "<";
                    case "<=" -> ">=";
                    case ">=" -> "<=";
                    default -> operator;
                };
            }
            if (key.key < 0 || literal.key >= 0 || literal.literal == null) {
                throw new Unsupported();
            }
            KeyType type = keyType(keys.get(key.key));
            if (type == null) {
                throw new Unsupported();
            }
            String op = switch (effective) {
                case "=", "<>", "!=", "<", ">", "<=", ">=" -> effective.equals("!=") ? "<>" : effective;
                default -> throw new Unsupported();
            };
            int index = key.key;
            return switch (type) {
                case INTEGER -> {
                    BigInteger bound = integerLiteral(literal.literal);
                    yield row -> compare(row.value(index), op, value -> new BigInteger(value.trim()).compareTo(bound));
                }
                case DATE -> {
                    LocalDate bound = dateLiteral(literal.literal);
                    yield row -> compare(row.value(index), op, value -> parseDate(value).compareTo(bound));
                }
                case STRING -> {
                    if (!"=".equals(op) && !"<>".equals(op) || !literal.literal.quoted) {
                        throw new Unsupported();
                    }
                    String bound = literal.literal.text;
                    yield row -> compare(row.value(index), op, value -> value.equals(bound) ? 0 : 1);
                }
            };
        }

        private interface Comparer {
            int compareTo(String value);
        }

        private static Tri compare(String value, String op, Comparer comparer) {
            if (value == null) {
                return Tri.UNKNOWN;
            }
            int order;
            try {
                order = comparer.compareTo(value);
            } catch (NumberFormatException | DateTimeParseException ignored) {
                throw new Undecidable();
            }
            boolean result = switch (op) {
                case "=" -> order == 0;
                case "<>" -> order != 0;
                case "<" -> order < 0;
                case ">" -> order > 0;
                case "<=" -> order <= 0;
                default -> order >= 0;
            };
            return result ? Tri.TRUE : Tri.FALSE;
        }

        private static LocalDate parseDate(String value) {
            return LocalDate.parse(value.trim());
        }

        private static BigInteger integerLiteral(Token literal) {
            try {
                return new BigInteger(literal.text.trim());
            } catch (NumberFormatException expected) {
                throw new Unsupported();
            }
        }

        private static LocalDate dateLiteral(Token literal) {
            if (!literal.quoted) {
                throw new Unsupported();
            }
            try {
                return parseDate(literal.text);
            } catch (DateTimeParseException expected) {
                throw new Unsupported();
            }
        }

        private record Operand(int key, Token literal) { }

        private Operand operand() {
            if (position >= tokens.size()) {
                throw new Unsupported();
            }
            Token token = tokens.get(position);
            if (token.kind == Kind.STRING) {
                position++;
                return new Operand(-1, new Token(Kind.STRING, token.text, true));
            }
            boolean negative = token.kind == Kind.OP && "-".equals(token.text);
            if (negative || token.kind == Kind.OP && "+".equals(token.text)) {
                position++;
                if (position >= tokens.size() || tokens.get(position).kind != Kind.NUMBER) {
                    throw new Unsupported();
                }
                Token number = tokens.get(position++);
                return new Operand(-1, new Token(Kind.NUMBER, (negative ? "-" : "") + number.text, false));
            }
            if (token.kind == Kind.NUMBER) {
                position++;
                return new Operand(-1, new Token(Kind.NUMBER, token.text, false));
            }
            return new Operand(keyReference(), null);
        }

        private int keyReference() {
            List<Token> words = new ArrayList<>();
            while (position < tokens.size() && (tokens.get(position).kind == Kind.WORD
                    || tokens.get(position).kind == Kind.QUOTED_WORD)) {
                words.add(tokens.get(position++));
                if (position < tokens.size() && tokens.get(position).kind == Kind.OP
                        && ".".equals(tokens.get(position).text)) {
                    position++;
                } else {
                    break;
                }
            }
            if (words.isEmpty()) {
                throw new Unsupported();
            }
            Token name = words.getLast();
            List<String> qualifier = words.subList(0, words.size() - 1).stream().map(Token::text).toList();
            if (!qualifier.isEmpty() && !qualifiers.contains(qualifier)) {
                throw new Unsupported();
            }
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i).getName();
                boolean same = name.kind == Kind.QUOTED_WORD ? name.text.equals(key) : name.text.equalsIgnoreCase(key);
                if (same) {
                    return i;
                }
            }
            throw new Unsupported();
        }

        private boolean word(String text) {
            return position < tokens.size() && tokens.get(position).isWord(text);
        }

        private boolean peekKind(Kind kind) {
            return position < tokens.size() && tokens.get(position).kind == kind;
        }

        private void expect(Kind kind) {
            if (!peekKind(kind)) {
                throw new Unsupported();
            }
            position++;
        }
    }

    // ---- tokenizer ----

    private enum Kind { WORD, QUOTED_WORD, STRING, NUMBER, OP, LPAREN, RPAREN, COMMA, SEMICOLON, OTHER }

    private record Token(Kind kind, String text, boolean quoted) {
        Token(Kind kind, String text) {
            this(kind, text, kind == Kind.STRING);
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equals(word);
        }
    }

    private static final class Tokens {
        final List<Token> list;
        int[] depth;
        final boolean unsafe;

        Tokens(List<Token> list, boolean unsafe) {
            this.list = list;
            this.unsafe = unsafe;
        }

        boolean balanced() {
            depth = new int[list.size()];
            int level = 0;
            int caseCount = 0;
            for (int i = 0; i < list.size(); i++) {
                Token token = list.get(i);
                Kind kind = token.kind;
                if (kind == Kind.RPAREN) {
                    level--;
                    if (level < 0) {
                        return false;
                    }
                }
                depth[i] = level;
                if (kind == Kind.LPAREN) {
                    level++;
                }
                if (token.isWord("case")) {
                    caseCount++;
                } else if (token.isWord("end")) {
                    caseCount--;
                    if (caseCount < 0) {
                        return false;
                    }
                }
                if (kind == Kind.SEMICOLON) {
                    if (level != 0 || caseCount != 0) {
                        return false;
                    }
                }
            }
            return level == 0 && caseCount == 0;
        }
    }

    private record Chain(int start, int end, List<Token> words) { }

    private static Chain chainAt(Tokens tokens, int index) {
        Token first = tokens.list.get(index);
        if (first.kind != Kind.WORD && first.kind != Kind.QUOTED_WORD) {
            return null;
        }
        if (index > 0 && tokens.list.get(index - 1).kind == Kind.OP && ".".equals(tokens.list.get(index - 1).text)) {
            return null;
        }
        List<Token> words = new ArrayList<>();
        int i = index;
        while (i < tokens.list.size() && (tokens.list.get(i).kind == Kind.WORD
                || tokens.list.get(i).kind == Kind.QUOTED_WORD)) {
            words.add(tokens.list.get(i++));
            if (i + 1 < tokens.list.size() && tokens.list.get(i).kind == Kind.OP && ".".equals(tokens.list.get(i).text)
                    && (tokens.list.get(i + 1).kind == Kind.WORD || tokens.list.get(i + 1).kind == Kind.QUOTED_WORD)) {
                i++;
            } else {
                break;
            }
        }
        return new Chain(index, i, words);
    }

    /** A name exactly as the scanner reads a table: {@code schema.table} or {@code database.schema.table}. */
    private static boolean isTable(Chain chain, String schema, String table) {
        List<Token> words = chain.words;
        if (words.size() == 2) {
            return words.get(0).text.equals(schema) && words.get(1).text.equals(table);
        }
        return words.size() == 3 && words.get(1).text.equals(schema) && words.get(2).text.equals(table);
    }

    private static Tokens tokenize(String sql) {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize(sql);
        List<Token> list = new ArrayList<>(result.tokens().size());
        for (SpectrumSqlLexer.Token t : result.tokens()) {
            Kind kind = switch (t.kind()) {
                case WORD -> Kind.WORD;
                case QUOTED_WORD -> Kind.QUOTED_WORD;
                case STRING -> Kind.STRING;
                case NUMBER -> Kind.NUMBER;
                case OP -> Kind.OP;
                case LPAREN -> Kind.LPAREN;
                case RPAREN -> Kind.RPAREN;
                case COMMA -> Kind.COMMA;
                case SEMICOLON -> Kind.SEMICOLON;
                case DOLLAR_QUOTED, OTHER -> Kind.OTHER;
            };
            list.add(new Token(kind, t.text(), t.quoted()));
        }
        return new Tokens(list, result.unsafe());
    }
}
