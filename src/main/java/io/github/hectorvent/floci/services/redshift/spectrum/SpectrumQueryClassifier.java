package io.github.hectorvent.floci.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
public final class SpectrumQueryClassifier {

    public Optional<SpectrumQuery> classify(String sql, int parameterCount) {
        String normalized = sql == null ? "" : sql.trim();
        if (!startsWithKeyword(normalized, "SELECT")) {
            return Optional.empty();
        }
        if (parameterCount > 0 || containsOutsideQuotes(normalized, '?')) {
            throw unsupported("parameterized Spectrum queries are not supported");
        }
        String statement = stripTrailingSemicolon(normalized);
        if (statement.indexOf(';') >= 0 || hasParenthesesOutsideQuotes(statement)) {
            throw unsupported("unsupported Spectrum query shape");
        }
        String lower = statement.toLowerCase(Locale.ROOT);
        for (String keyword : new String[]{" join ", " group ", " order ", " having ", " union ", " intersect ",
                " except ", " limit ", " offset ", " distinct "}) {
            if (lower.contains(keyword)) {
                throw unsupported("unsupported Spectrum query shape");
            }
        }
        int from = keywordIndex(statement, "from", 6);
        if (from < 0) {
            throw unsupported("Spectrum query requires a FROM clause");
        }
        String projection = statement.substring(6, from).trim();
        if (projection.isEmpty() || projection.contains(",") && projection.contains("(")) {
            throw unsupported("unsupported Spectrum projection");
        }
        String source = statement.substring(from + 4).trim();
        int where = keywordIndex(source, "where", 0);
        String tablePart = where < 0 ? source : source.substring(0, where).trim();
        String predicate = where < 0 ? null : source.substring(where + 5).trim();
        if (!isIdentifierPath(tablePart) || (predicate != null && !simplePredicate(predicate))) {
            throw unsupported("unsupported Spectrum query shape");
        }
        String[] identifiers = tablePart.split("\\.", -1);
        String schema = identifiers.length == 2 ? identifier(identifiers[0]) : null;
        String table = identifier(identifiers[identifiers.length - 1]);
        boolean star = "*".equals(projection);
        if (!star && !isProjection(projection)) {
            throw unsupported("unsupported Spectrum projection");
        }
        return Optional.of(new SpectrumQuery(schema, table, projection, predicate, star));
    }

    private static boolean simplePredicate(String predicate) {
        String lower = predicate.toLowerCase(Locale.ROOT);
        return !lower.contains(" or ") && !lower.contains(" in ") && !lower.contains(" like ")
                && !lower.contains(" is ") && predicate.matches("[\\w\\\".]+\\s*(=|<>|!=|<=|>=|<|>)\\s*('[^']*(?:''[^']*)*'|-?\\d+(?:\\.\\d+)?)"
                + "(?:\\s+AND\\s+[\\w\\\".]+\\s*(?:=|<>|!=|<=|>=|<|>)\\s*(?:'[^']*(?:''[^']*)*'|-?\\d+(?:\\.\\d+)?))*");
    }

    private static boolean isProjection(String projection) {
        for (String part : projection.split(",")) {
            if (!isIdentifierPath(part.trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierPath(String value) {
        return value.matches("(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:\"\"|[^\"])+\")(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:\"\"|[^\"])+\")){0,1}");
    }

    private static String identifier(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static int keywordIndex(String sql, String keyword, int start) {
        String lower = sql.toLowerCase(Locale.ROOT);
        for (int i = start; i <= lower.length() - keyword.length(); i++) {
            if (lower.regionMatches(i, keyword, 0, keyword.length())
                    && (i == 0 || Character.isWhitespace(lower.charAt(i - 1)))
                    && (i + keyword.length() == lower.length() || Character.isWhitespace(lower.charAt(i + keyword.length())))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWithKeyword(String sql, String keyword) {
        return sql.regionMatches(true, 0, keyword, 0, keyword.length())
                && (sql.length() == keyword.length() || Character.isWhitespace(sql.charAt(keyword.length())));
    }

    private static String stripTrailingSemicolon(String sql) {
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
    }

    private static boolean containsOutsideQuotes(String sql, char target) {
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\'') {
                quoted = !quoted;
            } else if (!quoted && current == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasParenthesesOutsideQuotes(String sql) {
        return containsOutsideQuotes(sql, '(') || containsOutsideQuotes(sql, ')');
    }

    private static SpectrumSqlException unsupported(String message) {
        return new SpectrumSqlException("0A000", message);
    }
}
