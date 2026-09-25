package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        boolean tableContext = false;
        for (Token token : tokenize(sql)) {
            if (!token.isChain()) {
                switch (token.symbol()) {
                    case '(' -> outerContexts.push(tableContext);
                    case ')' -> tableContext = !outerContexts.isEmpty() && outerContexts.pop();
                    case ';' -> {
                        tableContext = false;
                        outerContexts.clear();
                    }
                    default -> {
                        // a comma keeps the current context: it separates items of the same list
                    }
                }
                continue;
            }
            String keyword = token.keyword();
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
     * The first external table an {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code MERGE} or
     * {@code TRUNCATE} anywhere in {@code sql} writes to: every statement of a batch and every
     * data-modifying CTE is checked, not only the first write.
     */
    public static Optional<Reference> writeTarget(String sql, Set<String> schemaNames) {
        if (sql == null) {
            return Optional.empty();
        }
        List<Token> tokens = tokenize(sql);
        for (int i = 0; i < tokens.size(); i++) {
            int target = writeTargetIndex(tokens, i);
            if (target >= 0 && target < tokens.size() && tokens.get(target).isChain()) {
                Reference reference = reference(tokens.get(target).chain(), schemaNames);
                if (reference != null) {
                    return Optional.of(reference);
                }
            }
        }
        return Optional.empty();
    }

    private static int writeTargetIndex(List<Token> tokens, int i) {
        if (keywordAt(tokens, i, "insert") && keywordAt(tokens, i + 1, "into")) {
            return skipOnly(tokens, i + 2);
        }
        if (keywordAt(tokens, i, "merge") && keywordAt(tokens, i + 1, "into")) {
            return i + 2;
        }
        if (keywordAt(tokens, i, "update")) {
            return skipOnly(tokens, i + 1);
        }
        if (keywordAt(tokens, i, "delete") && keywordAt(tokens, i + 1, "from")) {
            return skipOnly(tokens, i + 2);
        }
        if (keywordAt(tokens, i, "truncate")) {
            return skipOnly(tokens, keywordAt(tokens, i + 1, "table") ? i + 2 : i + 1);
        }
        return -1;
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
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-'
                    || c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = skip(sql, i);
            } else if (c == '"' || Character.isLetter(c) || c == '_') {
                List<Word> chain = new ArrayList<>();
                i = read(sql, i, chain);
                while (true) {
                    int separator = skipTrivia(sql, i);
                    if (separator >= sql.length() || sql.charAt(separator) != '.') {
                        break;
                    }
                    int next = skipTrivia(sql, separator + 1);
                    if (next >= sql.length() || !(sql.charAt(next) == '"' || Character.isLetter(sql.charAt(next))
                            || sql.charAt(next) == '_')) {
                        break;
                    }
                    i = read(sql, next, chain);
                }
                result.add(new Token(chain, '\0'));
            } else {
                if (c == '(' || c == ')' || c == ',' || c == ';') {
                    result.add(new Token(null, c));
                }
                i++;
            }
        }
        return result;
    }

    private static int skipTrivia(String sql, int start) {
        int i = start;
        while (i < sql.length()) {
            if (Character.isWhitespace(sql.charAt(i))) {
                i++;
            } else if (sql.charAt(i) == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-'
                    || sql.charAt(i) == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = skip(sql, i);
            } else {
                break;
            }
        }
        return i;
    }

    private static int read(String sql, int start, List<Word> chain) {
        if (sql.charAt(start) == '"') {
            StringBuilder value = new StringBuilder();
            int i = start + 1;
            while (i < sql.length()) {
                if (sql.charAt(i) == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                        value.append('"');
                        i += 2;
                    } else {
                        i++;
                        break;
                    }
                } else {
                    value.append(sql.charAt(i++));
                }
            }
            chain.add(new Word(value.toString(), true));
            return i;
        }
        int i = start;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'
                || sql.charAt(i) == '$')) {
            i++;
        }
        chain.add(new Word(sql.substring(start, i).toLowerCase(Locale.ROOT), false));
        return i;
    }

    private static int skip(String sql, int start) {
        if (sql.charAt(start) == '\'') {
            int i = start + 1;
            while (i < sql.length()) {
                if (sql.charAt(i) == '\'' && (i + 1 >= sql.length() || sql.charAt(i + 1) != '\'')) {
                    return i + 1;
                }
                i += sql.charAt(i) == '\'' ? 2 : 1;
            }
            return i;
        }
        if (sql.charAt(start) == '-') {
            int i = sql.indexOf('\n', start + 2);
            return i < 0 ? sql.length() : i;
        }
        int i = sql.indexOf("*/", start + 2);
        return i < 0 ? sql.length() : i + 2;
    }
}
