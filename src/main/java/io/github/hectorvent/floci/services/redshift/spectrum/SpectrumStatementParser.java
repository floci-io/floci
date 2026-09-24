package io.github.hectorvent.floci.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public final class SpectrumStatementParser {

    private static final String UNSUPPORTED = "0A000";
    private static final String INVALID_VALUE = "22023";

    public Optional<SpectrumStatement> parse(String sql) {
        List<Token> tokens = new Tokenizer(sql).tokenize();
        Cursor cursor = new Cursor(tokens);
        if (cursor.peekKeyword("CREATE") && cursor.peekKeyword(1, "EXTERNAL") && cursor.peekKeyword(2, "SCHEMA")) {
            return Optional.of(parseSchema(cursor));
        }
        if (cursor.peekKeyword("CREATE") && cursor.peekKeyword(1, "EXTERNAL") && cursor.peekKeyword(2, "TABLE")) {
            return Optional.of(parseTable(cursor));
        }
        if (cursor.peekKeyword("CREATE") && cursor.peekKeyword(1, "EXTERNAL")) {
            throw unsupported("unsupported CREATE EXTERNAL statement");
        }
        return Optional.empty();
    }

    private SpectrumStatement.CreateSchema parseSchema(Cursor cursor) {
        cursor.expectKeyword("CREATE");
        cursor.expectKeyword("EXTERNAL");
        cursor.expectKeyword("SCHEMA");
        String schema = cursor.identifier();
        cursor.expectKeyword("FROM");
        cursor.expectKeyword("DATA");
        cursor.expectKeyword("CATALOG");
        cursor.expectKeyword("DATABASE");
        String database = cursor.stringLiteral();
        cursor.expectKeyword("IAM_ROLE");
        String role = cursor.stringLiteral();
        if (cursor.peekKeyword("CREATE")) {
            cursor.expectKeyword("CREATE");
            cursor.expectKeyword("EXTERNAL");
            cursor.expectKeyword("DATABASE");
            cursor.expectKeyword("IF");
            cursor.expectKeyword("NOT");
            cursor.expectKeyword("EXISTS");
        }
        cursor.finish();
        return new SpectrumStatement.CreateSchema(schema, database, role);
    }

    private SpectrumStatement.CreateTable parseTable(Cursor cursor) {
        cursor.expectKeyword("CREATE");
        cursor.expectKeyword("EXTERNAL");
        cursor.expectKeyword("TABLE");
        String schema = cursor.identifier();
        cursor.expectSymbol('.');
        String table = cursor.identifier();
        cursor.expectSymbol('(');
        List<SpectrumColumn> columns = new ArrayList<>();
        while (!cursor.peekSymbol(')')) {
            String name = cursor.identifier();
            String type = cursor.identifier();
            try {
                columns.add(new SpectrumColumn(name, SpectrumColumn.Type.fromSql(type)));
            } catch (IllegalArgumentException exception) {
                throw new SpectrumSqlException(INVALID_VALUE, exception.getMessage());
            }
            if (!cursor.consumeSymbol(',')) {
                break;
            }
        }
        cursor.expectSymbol(')');
        cursor.expectKeyword("STORED");
        cursor.expectKeyword("AS");
        if (!cursor.consumeKeyword("TEXTFILE")) {
            throw unsupported("only STORED AS TEXTFILE is supported");
        }
        cursor.expectKeyword("LOCATION");
        String location = cursor.stringLiteral();
        String nullValue = "\\N";
        char delimiter = ',';
        char quote = '"';
        char escape = '\\';
        int headerLines = 0;
        if (cursor.consumeKeyword("TBLPROPERTIES")) {
            cursor.expectSymbol('(');
            Map<String, String> properties = new HashMap<>();
            while (!cursor.peekSymbol(')')) {
                String name = cursor.stringLiteral();
                cursor.expectSymbol('=');
                String value = cursor.stringLiteral();
                if (properties.put(name, value) != null) {
                    throw unsupported("duplicate Spectrum table property: " + name);
                }
                if (!SetOfProperties.SUPPORTED.contains(name)) {
                    throw unsupported("unsupported Spectrum table property: " + name);
                }
                if (!cursor.consumeSymbol(',')) {
                    break;
                }
            }
            cursor.expectSymbol(')');
            try {
                if (properties.containsKey("skip.header.line.count")) {
                    headerLines = Integer.parseInt(properties.get("skip.header.line.count"));
                    if (headerLines < 0) {
                        throw new IllegalArgumentException("skip.header.line.count must not be negative");
                    }
                }
                delimiter = oneCharacter(properties, "field.delim", delimiter);
                char serialization = oneCharacter(properties, "serialization.format", delimiter);
                if (properties.containsKey("field.delim") && properties.containsKey("serialization.format")
                        && serialization != delimiter) {
                    throw new IllegalArgumentException("field.delim and serialization.format must match");
                }
                delimiter = serialization;
                quote = oneCharacter(properties, "quoteChar", quote);
                escape = oneCharacter(properties, "escapeChar", escape);
                nullValue = properties.getOrDefault("serialization.null.format", nullValue);
            } catch (IllegalArgumentException exception) {
                throw new SpectrumSqlException(INVALID_VALUE, exception.getMessage());
            }
        }
        cursor.finish();
        try {
            SpectrumExternalSchema.validateS3Location(location);
        } catch (IllegalArgumentException exception) {
            throw new SpectrumSqlException(INVALID_VALUE, exception.getMessage());
        }
        return new SpectrumStatement.CreateTable(schema, table, columns, location, delimiter, quote, escape, nullValue,
                headerLines);
    }

    private static char oneCharacter(Map<String, String> properties, String name, char defaultValue) {
        String value = properties.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value.length() != 1) {
            throw new IllegalArgumentException(name + " must contain exactly one character");
        }
        return value.charAt(0);
    }

    private static SpectrumSqlException unsupported(String message) {
        return new SpectrumSqlException(UNSUPPORTED, message);
    }

    private static final class SetOfProperties {
        private static final Set<String> SUPPORTED = Set.of(
                "skip.header.line.count", "field.delim", "serialization.format", "quoteChar", "escapeChar",
                "serialization.null.format");

        private SetOfProperties() {
        }
    }

    private record Token(Kind kind, String text, boolean quoted) {
        private enum Kind { IDENTIFIER, STRING, SYMBOL, END }
    }

    private static final class Cursor {
        private final List<Token> tokens;
        private int position;

        private Cursor(List<Token> tokens) {
            this.tokens = tokens;
        }

        private boolean peekKeyword(String value) {
            return peekKeyword(0, value);
        }

        private boolean peekKeyword(int offset, String value) {
            Token token = tokens.get(Math.min(position + offset, tokens.size() - 1));
            return token.kind() == Token.Kind.IDENTIFIER && !token.quoted() && token.text().equalsIgnoreCase(value);
        }

        private boolean consumeKeyword(String value) {
            if (!peekKeyword(value)) {
                return false;
            }
            position++;
            return true;
        }

        private void expectKeyword(String value) {
            if (!consumeKeyword(value)) {
                throw unsupported("expected keyword " + value);
            }
        }

        private String identifier() {
            Token token = current();
            if (token.kind() != Token.Kind.IDENTIFIER) {
                throw unsupported("expected identifier");
            }
            position++;
            return token.text();
        }

        private String stringLiteral() {
            Token token = current();
            if (token.kind() != Token.Kind.STRING) {
                throw unsupported("expected string literal");
            }
            position++;
            return token.text();
        }

        private void expectSymbol(char symbol) {
            if (!consumeSymbol(symbol)) {
                throw unsupported("expected symbol " + symbol);
            }
        }

        private boolean consumeSymbol(char symbol) {
            Token token = current();
            if (token.kind() == Token.Kind.SYMBOL && token.text().charAt(0) == symbol) {
                position++;
                return true;
            }
            return false;
        }

        private boolean peekSymbol(char symbol) {
            Token token = current();
            return token.kind() == Token.Kind.SYMBOL && token.text().charAt(0) == symbol;
        }

        private void finish() {
            if (consumeSymbol(';')) {
                if (current().kind() != Token.Kind.END) {
                    throw unsupported("trailing SQL is not supported");
                }
                return;
            }
            if (current().kind() != Token.Kind.END) {
                throw unsupported("trailing SQL is not supported");
            }
        }

        private Token current() {
            return tokens.get(position);
        }
    }

    private static final class Tokenizer {
        private final String sql;
        private int position;

        private Tokenizer(String sql) {
            this.sql = sql == null ? "" : sql;
        }

        private List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (position < sql.length()) {
                char current = sql.charAt(position);
                if (Character.isWhitespace(current)) {
                    position++;
                } else if (current == '-' && position + 1 < sql.length() && sql.charAt(position + 1) == '-') {
                    skipComment();
                } else if (current == '\'') {
                    tokens.add(new Token(Token.Kind.STRING, readQuoted('\''), true));
                } else if (current == '"') {
                    tokens.add(new Token(Token.Kind.IDENTIFIER, readQuoted('"'), true));
                } else if (isSymbol(current)) {
                    tokens.add(new Token(Token.Kind.SYMBOL, String.valueOf(current), false));
                    position++;
                } else {
                    tokens.add(new Token(Token.Kind.IDENTIFIER, readIdentifier(), false));
                }
            }
            tokens.add(new Token(Token.Kind.END, "", false));
            return tokens;
        }

        private void skipComment() {
            position += 2;
            while (position < sql.length() && sql.charAt(position) != '\n') {
                position++;
            }
        }

        private String readQuoted(char delimiter) {
            position++;
            StringBuilder value = new StringBuilder();
            while (position < sql.length()) {
                char current = sql.charAt(position++);
                if (current == delimiter) {
                    if (position < sql.length() && sql.charAt(position) == delimiter) {
                        value.append(delimiter);
                        position++;
                    } else {
                        return value.toString();
                    }
                } else {
                    value.append(current);
                }
            }
            throw unsupported("unterminated quoted value");
        }

        private String readIdentifier() {
            int start = position;
            while (position < sql.length()) {
                char current = sql.charAt(position);
                if (Character.isWhitespace(current) || isSymbol(current) || current == '\'') {
                    break;
                }
                position++;
            }
            return sql.substring(start, position).toLowerCase(Locale.ROOT);
        }

        private static boolean isSymbol(char value) {
            return value == '(' || value == ')' || value == ',' || value == '.' || value == ';' || value == '=';
        }
    }
}
