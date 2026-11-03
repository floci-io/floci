package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumSqlLexerTest {

    @Test
    void handlesNullAndEmptySql() {
        SpectrumSqlLexer.Result nullResult = SpectrumSqlLexer.tokenize(null);
        assertTrue(nullResult.tokens().isEmpty());
        assertFalse(nullResult.unsafe());

        SpectrumSqlLexer.Result emptyResult = SpectrumSqlLexer.tokenize("");
        assertTrue(emptyResult.tokens().isEmpty());
        assertFalse(emptyResult.unsafe());
    }

    @Test
    void tokenizesWordsCaseInsensitively() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT Col_One FROM Table_A");
        assertFalse(result.unsafe());
        List<SpectrumSqlLexer.Token> tokens = result.tokens();
        assertEquals(4, tokens.size());
        assertEquals(SpectrumSqlLexer.Kind.WORD, tokens.get(0).kind());
        assertEquals("select", tokens.get(0).text());
        assertEquals(SpectrumSqlLexer.Kind.WORD, tokens.get(1).kind());
        assertEquals("col_one", tokens.get(1).text());
        assertEquals(SpectrumSqlLexer.Kind.WORD, tokens.get(2).kind());
        assertEquals("from", tokens.get(2).text());
        assertEquals(SpectrumSqlLexer.Kind.WORD, tokens.get(3).kind());
        assertEquals("table_a", tokens.get(3).text());
    }

    @Test
    void stripsLineAndBlockComments() {
        String sql = """
                -- single line comment
                SELECT /* inline comment */ 1
                /* multi-line
                   comment */
                /* nested /* block */ comment */
                FROM t
                """;
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize(sql);
        assertFalse(result.unsafe());
        List<String> texts = result.tokens().stream().map(SpectrumSqlLexer.Token::text).toList();
        assertThat(texts, equalTo(List.of("select", "1", "from", "t")));
    }

    @Test
    void flagsUnterminatedBlockCommentAsUnsafe() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT 1 /* unterminated");
        assertTrue(result.unsafe());
    }

    @Test
    void parsesQuotedIdentifiersAndEscapes() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT \"My \"\"Quoted\"\" Col\" FROM \"T\"");
        assertFalse(result.unsafe());
        List<SpectrumSqlLexer.Token> tokens = result.tokens();
        assertEquals(4, tokens.size());
        assertEquals(SpectrumSqlLexer.Kind.QUOTED_WORD, tokens.get(1).kind());
        assertEquals("My \"Quoted\" Col", tokens.get(1).text());
        assertTrue(tokens.get(1).quoted());
        assertEquals(SpectrumSqlLexer.Kind.QUOTED_WORD, tokens.get(3).kind());
        assertEquals("T", tokens.get(3).text());
    }

    @Test
    void flagsUnterminatedQuotedIdentifierAsUnsafe() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT \"unterminated FROM t");
        assertTrue(result.unsafe());
    }

    @Test
    void parsesStringLiteralsAndEscapes() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT 'it''s a value', 'regular'");
        assertFalse(result.unsafe());
        List<SpectrumSqlLexer.Token> tokens = result.tokens();
        assertEquals(4, tokens.size());
        assertEquals(SpectrumSqlLexer.Kind.STRING, tokens.get(1).kind());
        assertEquals("it's a value", tokens.get(1).text());
        assertTrue(tokens.get(1).quoted());
        assertEquals(SpectrumSqlLexer.Kind.COMMA, tokens.get(2).kind());
        assertEquals(SpectrumSqlLexer.Kind.STRING, tokens.get(3).kind());
        assertEquals("regular", tokens.get(3).text());
    }

    @Test
    void parsesEscapeStringConstantsAndMarksUnsafe() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT E'hello \\'world\\'', e'line\\n'");
        assertTrue(result.unsafe());
        List<SpectrumSqlLexer.Token> tokens = result.tokens();
        assertEquals(6, tokens.size());
        assertEquals("select", tokens.get(0).text());
        assertEquals("e", tokens.get(1).text());
        assertEquals("hello 'world'", tokens.get(2).text());
        assertEquals(",", tokens.get(3).text());
        assertEquals("e", tokens.get(4).text());
        assertEquals("linen", tokens.get(5).text());
    }

    @Test
    void flagsUnterminatedStringAsUnsafe() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT 'unterminated");
        assertTrue(result.unsafe());
    }

    @Test
    void handlesDollarQuotedBlocksAndMarksUnsafe() {
        SpectrumSqlLexer.Result anonymous = SpectrumSqlLexer.tokenize("SELECT $$function body$$ FROM t");
        assertTrue(anonymous.unsafe());
        assertEquals(4, anonymous.tokens().size());
        assertEquals(SpectrumSqlLexer.Kind.DOLLAR_QUOTED, anonymous.tokens().get(1).kind());
        assertEquals("$$function body$$", anonymous.tokens().get(1).text());

        SpectrumSqlLexer.Result tagged = SpectrumSqlLexer.tokenize("SELECT $tag$body$tag$ FROM t");
        assertTrue(tagged.unsafe());
        assertEquals(4, tagged.tokens().size());
        assertEquals(SpectrumSqlLexer.Kind.DOLLAR_QUOTED, tagged.tokens().get(1).kind());
        assertEquals("$tag$body$tag$", tagged.tokens().get(1).text());
    }

    @Test
    void handlesPositionalParameters() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT * FROM t WHERE id = $1 AND val = $12");
        assertFalse(result.unsafe());
        List<SpectrumSqlLexer.Token> params = result.tokens().stream()
                .filter(t -> t.text().startsWith("$")).toList();
        assertEquals(2, params.size());
        assertEquals("$1", params.get(0).text());
        assertEquals(SpectrumSqlLexer.Kind.OTHER, params.get(0).kind());
        assertEquals("$12", params.get(1).text());
    }

    @Test
    void parsesOperatorsAndPunctuation() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT a::int <= 10 AND b >= 20 AND c <> 30 AND d != 40;");
        assertFalse(result.unsafe());
        List<SpectrumSqlLexer.Token> tokens = result.tokens();
        List<String> opTexts = tokens.stream().filter(t -> t.kind() == SpectrumSqlLexer.Kind.OP).map(SpectrumSqlLexer.Token::text).toList();
        assertThat(opTexts, equalTo(List.of("<=", ">=", "<>", "!=")));
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == SpectrumSqlLexer.Kind.SEMICOLON));
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == SpectrumSqlLexer.Kind.OTHER && t.text().equals("::")));
    }

    @Test
    void parsesNumbers() {
        SpectrumSqlLexer.Result result = SpectrumSqlLexer.tokenize("SELECT 42, 3.14159, 100");
        assertFalse(result.unsafe());
        List<SpectrumSqlLexer.Token> numbers = result.tokens().stream()
                .filter(t -> t.kind() == SpectrumSqlLexer.Kind.NUMBER).toList();
        assertEquals(3, numbers.size());
        assertEquals("42", numbers.get(0).text());
        assertEquals("3.14159", numbers.get(1).text());
        assertEquals("100", numbers.get(2).text());
    }

    @Test
    void flagsCustomPrefixStringsAsUnsafe() {
        SpectrumSqlLexer.Result unicode = SpectrumSqlLexer.tokenize("SELECT u&'d\\0061t\\0061'");
        assertTrue(unicode.unsafe());

        SpectrumSqlLexer.Result bit = SpectrumSqlLexer.tokenize("SELECT b'101'");
        assertTrue(bit.unsafe());
    }
}
