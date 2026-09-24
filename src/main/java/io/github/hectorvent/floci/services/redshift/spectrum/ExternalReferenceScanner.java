package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds references to bound external schemas while ignoring literals and comments. */
public final class ExternalReferenceScanner {
    private static final Pattern WRITE_TARGET = Pattern.compile("(?is)\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|TRUNCATE(?:\\s+TABLE)?)\\s+((?:\\\"(?:\\\"\\\"|[^\\\"])*\\\"|[\\w$]+)(?:\\s*\\.\\s*(?:\\\"(?:\\\"\\\"|[^\\\"])*\\\"|[\\w$]+)){1,2})");
    public record Reference(String schema, String table) {
    }
    private record Word(String value, boolean quoted) {
    }
    private ExternalReferenceScanner() {
    }

    public static Set<Reference> scan(String sql, Set<String> schemaNames) {
        Set<Reference> result = new LinkedHashSet<>();
        for (List<Word> chain : chains(sql)) {
            Reference reference = reference(chain, schemaNames);
            if (reference != null) {
                result.add(reference);
            }
        }
        return result;
    }

    public static boolean containsIdentifierPrefix(String sql, String prefix) {
        if (sql == null || prefix == null) {
            return false;
        }
        return chains(sql).stream().flatMap(List::stream)
                .anyMatch(word -> word.value().regionMatches(true, 0, prefix, 0, prefix.length()));
    }

    public static Optional<Reference> writeTarget(String sql, Set<String> schemaNames) {
        if (sql == null) {
            return Optional.empty();
        }
        Matcher matcher = WRITE_TARGET.matcher(mask(sql));
        if (!matcher.find()) return Optional.empty();
        String target = matcher.group(1).replaceAll("\\s*\\.\\s*", ".");
        List<List<Word>> targetChains = chains(target);
        return targetChains.isEmpty() ? Optional.empty()
                : Optional.ofNullable(reference(targetChains.getFirst(), schemaNames));
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

    private static List<List<Word>> chains(String sql) {
        List<List<Word>> result = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-' || c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
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
                    if (next >= sql.length() || !(sql.charAt(next) == '"' || Character.isLetter(sql.charAt(next)) || sql.charAt(next) == '_')) {
                        break;
                    }
                    i = read(sql, next, chain);
                }
                result.add(chain);
            } else {
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
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
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
        if (sql.charAt(start) == '-' ) {
            int i = sql.indexOf('\n', start + 2);
            return i < 0 ? sql.length() : i;
        }
        int i = sql.indexOf("*/", start + 2);
        return i < 0 ? sql.length() : i + 2;
    }

    private static String mask(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-' || c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = skip(sql, i);
                result.append(" ".repeat(end - i));
                i = end;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
}
