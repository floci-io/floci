package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds references to bound external schemas while ignoring literals and comments.
 *
 * <p>A qualified name only counts as a table reference where a table can appear: after
 * {@code FROM}, {@code JOIN}, {@code USING}, {@code INTO} or {@code UPDATE}, and in a comma-separated
 * {@code FROM} list. Anywhere else (a select list, {@code ON}, {@code WHERE}) {@code schema.name}
 * is a column qualifier, so a table aliased with an external schema's name is not mistaken for it.
 */
public final class ExternalReferenceScanner {

    /** Keywords after which the following names are table sources. */
    private static final Set<String> TABLE_INTRODUCERS = Set.of("from", "join", "using", "into", "update");

    /** Functions whose argument list uses FROM as an expression separator, not to start a table list. */
    private static final Set<String> EXPRESSION_FROM_FUNCTIONS = Set.of("extract", "substring", "trim", "overlay", "position");

    /** Keywords that end a table-source list: what follows is an expression or a new clause. */
    private static final Set<String> CLAUSE_ENDERS = Set.of("select", "where", "group", "order", "having",
            "limit", "offset", "union", "intersect", "except", "on", "set", "values", "returning", "window",
            "fetch", "qualify");

    public record Reference(String schema, String table) {
    }

    private record Word(String value, boolean quoted) {
    }

    /** An identifier chain ({@code a.b.c}) or one structural character. */
    private record Token(List<Word> chain, char symbol) {

        boolean isChain() {
            return chain != null;
        }

        boolean isKeyword(String keyword) {
            return chain != null && chain.size() == 1 && !chain.getFirst().quoted()
                    && chain.getFirst().value().equals(keyword);
        }

        String keyword() {
            return chain != null && chain.size() == 1 && !chain.getFirst().quoted() ? chain.getFirst().value() : null;
        }
    }

    private ExternalReferenceScanner() {
    }

    public static Set<Reference> scan(String sql, Set<String> schemaNames) {
        Set<Reference> result = new LinkedHashSet<>();
        Deque<Boolean> outerContexts = new ArrayDeque<>();
        Deque<Boolean> expressionFromParens = new ArrayDeque<>();
        boolean tableContext = false;
        String previousKeyword = null;
        for (Token token : tokenize(sql)) {
            if (!token.isChain()) {
                switch (token.symbol()) {
                    case '(' -> {
                        outerContexts.push(tableContext);
                        expressionFromParens.push(previousKeyword != null && EXPRESSION_FROM_FUNCTIONS.contains(previousKeyword));
                    }
                    case ')' -> {
                        tableContext = !outerContexts.isEmpty() && outerContexts.pop();
                        if (!expressionFromParens.isEmpty()) {
                            expressionFromParens.pop();
                        }
                    }
                    case ';' -> {
                        tableContext = false;
                        outerContexts.clear();
                        expressionFromParens.clear();
                    }
                    default -> {
                        // a comma keeps the current context: it separates items of the same list
                    }
                }
                previousKeyword = null;
                continue;
            }
            String keyword = token.keyword();
            boolean expressionFrom = "from".equals(keyword)
                    && ("distinct".equals(previousKeyword) || !expressionFromParens.isEmpty() && expressionFromParens.peek());
            previousKeyword = keyword;
            if (expressionFrom) {
                continue;
            }
            if (keyword != null && TABLE_INTRODUCERS.contains(keyword)) {
                tableContext = true;
            } else if (keyword != null && CLAUSE_ENDERS.contains(keyword)) {
                tableContext = false;
            } else if (tableContext) {
                Reference reference = reference(token.chain(), schemaNames);
                if (reference != null) {
                    result.add(reference);
                }
            }
        }
        return result;
    }

    public static boolean containsIdentifierPrefix(String sql, String prefix) {
        if (sql == null || prefix == null) {
            return false;
        }
        return tokenize(sql).stream().filter(Token::isChain).flatMap(token -> token.chain().stream())
                .anyMatch(word -> word.value().regionMatches(true, 0, prefix, 0, prefix.length()));
    }

    /**
     * The first external table or schema an {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code MERGE},
     * {@code TRUNCATE}, {@code COPY}, {@code SELECT INTO}, {@code CREATE TABLE}, {@code CREATE VIEW}, {@code CREATE INDEX},
     * {@code ALTER TABLE} or {@code DROP} anywhere in
     * {@code sql} changes: every statement of a batch and every data-modifying CTE is checked, not only
     * the first write. The external DDL Floci implements is parsed before this is reached.
     */
    public static Optional<Reference> writeTarget(String sql, Set<String> schemaNames) {
        if (sql == null) {
            return Optional.empty();
        }
        List<Token> tokens = tokenize(sql);
        for (int i = 0; i < tokens.size(); i++) {
            Optional<Reference> moved = movedIntoExternalSchema(tokens, i, schemaNames);
            if (moved.isPresent()) {
                return moved;
            }
            Optional<Reference> truncated = truncatedTarget(tokens, i, schemaNames);
            if (truncated.isPresent()) {
                return truncated;
            }
            int target = writeTargetIndex(tokens, i);
            if (target >= 0 && target < tokens.size() && tokens.get(target).isChain()) {
                Reference reference = reference(tokens.get(target).chain(), schemaNames);
                if (reference != null) {
                    return Optional.of(reference);
                }
            }
            Optional<Reference> dropped = droppedTarget(tokens, i, schemaNames);
            if (dropped.isPresent()) {
                return dropped;
            }
        }
        return Optional.empty();
    }

    private static Optional<Reference> movedIntoExternalSchema(List<Token> tokens, int i, Set<String> schemaNames) {
        if (!keywordAt(tokens, i, "alter") || !keywordAt(tokens, i + 1, "table")) {
            return Optional.empty();
        }
        int source = skipOnly(tokens, i + 2);
        if (keywordAt(tokens, source, "if") && keywordAt(tokens, source + 1, "exists")) {
            source += 2;
        }
        int operation = source + 1;
        if (keywordAt(tokens, operation, "rename") && keywordAt(tokens, operation + 1, "to")) {
            int target = operation + 2;
            if (target < tokens.size() && tokens.get(target).isChain()) {
                return Optional.ofNullable(reference(tokens.get(target).chain(), schemaNames));
            }
        }
        if (keywordAt(tokens, operation, "set") && keywordAt(tokens, operation + 1, "schema")) {
            int target = operation + 2;
            if (target < tokens.size() && tokens.get(target).isChain()) {
                Word schema = tokens.get(target).chain().getFirst();
                if (schemaNames.contains(schema.value())) {
                    return Optional.of(new Reference(schema.value(), ""));
                }
            }
        }
        return Optional.empty();
    }

    /** {@code DROP TABLE a, b} and {@code DROP SCHEMA a, b}: any name of the list may be an external one. */
    private static Optional<Reference> droppedTarget(List<Token> tokens, int i, Set<String> schemaNames) {
        if (!keywordAt(tokens, i, "drop")) {
            return Optional.empty();
        }
        boolean table = keywordAt(tokens, i + 1, "table");
        if (!table && !keywordAt(tokens, i + 1, "schema")) {
            return Optional.empty();
        }
        int index = i + 2;
        if (keywordAt(tokens, index, "if") && keywordAt(tokens, index + 1, "exists")) {
            index += 2;
        }
        while (index < tokens.size() && tokens.get(index).isChain()) {
            List<Word> chain = tokens.get(index).chain();
            Reference reference = table ? reference(chain, schemaNames)
                    : schemaNames.contains(chain.getFirst().value()) ? new Reference(chain.getFirst().value(), "") : null;
            if (reference != null) {
                return Optional.of(reference);
            }
            boolean anotherName = index + 1 < tokens.size() && !tokens.get(index + 1).isChain()
                    && tokens.get(index + 1).symbol() == ',';
            index += anotherName ? 2 : tokens.size();
        }
        return Optional.empty();
    }

    private static int writeTargetIndex(List<Token> tokens, int i) {
        if (keywordAt(tokens, i, "create")) {
            int index = i + 1;
            while (keywordAt(tokens, index, "temp") || keywordAt(tokens, index, "temporary")
                    || keywordAt(tokens, index, "unlogged") || keywordAt(tokens, index, "local")) {
                index++;
            }
            if (keywordAt(tokens, index, "or") && keywordAt(tokens, index + 1, "replace")) {
                index += 2;
                while (keywordAt(tokens, index, "temp") || keywordAt(tokens, index, "temporary")) {
                    index++;
                }
            }
            if (keywordAt(tokens, index, "materialized")) {
                index++;
            }
            if (keywordAt(tokens, index, "unique")) {
                index++;
            }
            if (keywordAt(tokens, index, "index")) {
                return indexTable(tokens, index + 1);
            }
            if (!(keywordAt(tokens, index, "table") || keywordAt(tokens, index, "view"))) {
                return -1;
            }
            index++;
            if (keywordAt(tokens, index, "if") && keywordAt(tokens, index + 1, "not") && keywordAt(tokens, index + 2, "exists")) {
                index += 3;
            }
            return index;
        }
        if (keywordAt(tokens, i, "alter") && keywordAt(tokens, i + 1, "table")) {
            int index = skipOnly(tokens, i + 2);
            if (keywordAt(tokens, index, "if") && keywordAt(tokens, index + 1, "exists")) {
                index += 2;
            }
            return skipOnly(tokens, index);
        }
        if (keywordAt(tokens, i, "copy")) {
            return i + 1;
        }
        if (keywordAt(tokens, i, "into")) {
            int index = i + 1;
            while (keywordAt(tokens, index, "temp") || keywordAt(tokens, index, "temporary")
                    || keywordAt(tokens, index, "unlogged") || keywordAt(tokens, index, "table")) {
                index++;
            }
            return skipOnly(tokens, index);
        }
        if (keywordAt(tokens, i, "update")) {
            return skipOnly(tokens, i + 1);
        }
        if (keywordAt(tokens, i, "delete") && keywordAt(tokens, i + 1, "from")) {
            return skipOnly(tokens, i + 2);
        }
        return -1;
    }

    /** {@code CREATE INDEX [CONCURRENTLY] [IF NOT EXISTS] name ON [ONLY] table}: {@code from} follows INDEX. */
    private static int indexTable(List<Token> tokens, int from) {
        for (int index = from; index < from + 6 && index < tokens.size(); index++) {
            if (keywordAt(tokens, index, "on")) {
                return skipOnly(tokens, index + 1);
            }
        }
        return -1;
    }

    private static Optional<Reference> truncatedTarget(List<Token> tokens, int i, Set<String> schemaNames) {
        if (!keywordAt(tokens, i, "truncate")) {
            return Optional.empty();
        }
        int target = skipOnly(tokens, keywordAt(tokens, i + 1, "table") ? i + 2 : i + 1);
        while (target < tokens.size() && tokens.get(target).isChain()) {
            Reference reference = reference(tokens.get(target).chain(), schemaNames);
            if (reference != null) {
                return Optional.of(reference);
            }
            int comma = target + 1;
            if (comma >= tokens.size() || tokens.get(comma).isChain() || tokens.get(comma).symbol() != ',') {
                return Optional.empty();
            }
            target = skipOnly(tokens, comma + 1);
        }
        return Optional.empty();
    }

    private static int skipOnly(List<Token> tokens, int index) {
        return keywordAt(tokens, index, "only") ? index + 1 : index;
    }

    private static boolean keywordAt(List<Token> tokens, int index, String keyword) {
        return index < tokens.size() && tokens.get(index).isKeyword(keyword);
    }

    private static Reference reference(List<Word> chain, Set<String> schemas) {
        if (chain.size() >= 2 && schemas.contains(chain.get(0).value())) {
            return new Reference(chain.get(0).value(), chain.get(1).value());
        }
        if (chain.size() >= 3 && schemas.contains(chain.get(1).value())) {
            return new Reference(chain.get(1).value(), chain.get(2).value());
        }
        return null;
    }

    private static List<Token> tokenize(String sql) {
        List<Token> result = new ArrayList<>();
        List<SpectrumSqlLexer.Token> raw = SpectrumSqlLexer.tokenize(sql).tokens();
        int i = 0;
        while (i < raw.size()) {
            SpectrumSqlLexer.Token t = raw.get(i);
            if (t.kind() == SpectrumSqlLexer.Kind.WORD || t.kind() == SpectrumSqlLexer.Kind.QUOTED_WORD) {
                List<Word> chain = new ArrayList<>();
                chain.add(new Word(t.text(), t.kind() == SpectrumSqlLexer.Kind.QUOTED_WORD));
                i++;
                while (i + 1 < raw.size() && raw.get(i).kind() == SpectrumSqlLexer.Kind.OP
                        && ".".equals(raw.get(i).text())
                        && (raw.get(i + 1).kind() == SpectrumSqlLexer.Kind.WORD
                                || raw.get(i + 1).kind() == SpectrumSqlLexer.Kind.QUOTED_WORD)) {
                    chain.add(new Word(raw.get(i + 1).text(), raw.get(i + 1).kind() == SpectrumSqlLexer.Kind.QUOTED_WORD));
                    i += 2;
                }
                result.add(new Token(chain, '\0'));
            } else {
                // by kind, not by text: the string ';' is not a statement separator
                char symbol = switch (t.kind()) {
                    case LPAREN -> '(';
                    case RPAREN -> ')';
                    case COMMA -> ',';
                    case SEMICOLON -> ';';
                    default -> '\0';
                };
                if (symbol != '\0') {
                    result.add(new Token(null, symbol));
                }
                i++;
            }
        }
        return result;
    }
}
