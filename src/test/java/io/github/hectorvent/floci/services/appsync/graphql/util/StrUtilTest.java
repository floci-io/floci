package io.github.hectorvent.floci.services.appsync.graphql.util;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class StrUtilTest {

    private final StrUtil str = new StrUtil();

    @Test
    void toUpper_basic() {
        assertThat(str.toUpper("hello"), is("HELLO"));
    }

    @Test
    void toUpper_mixed() {
        assertThat(str.toUpper("Hello World"), is("HELLO WORLD"));
    }

    @Test
    void toUpper_null() {
        assertThat(str.toUpper(null), is(""));
    }

    @Test
    void toUpper_empty() {
        assertThat(str.toUpper(""), is(""));
    }

    @Test
    void toLower_basic() {
        assertThat(str.toLower("HELLO"), is("hello"));
    }

    @Test
    void toLower_mixed() {
        assertThat(str.toLower("Hello World"), is("hello world"));
    }

    @Test
    void toLower_null() {
        assertThat(str.toLower(null), is(""));
    }

    @Test
    void toLower_empty() {
        assertThat(str.toLower(""), is(""));
    }

    @Test
    void toReplace_basic() {
        assertThat(str.toReplace("hello world", "world", "there"), is("hello there"));
    }

    @Test
    void toReplace_multiple() {
        assertThat(str.toReplace("aabbcc", "bb", "xx"), is("aaxxcc"));
    }

    @Test
    void toReplace_noMatch() {
        assertThat(str.toReplace("hello", "xyz", "abc"), is("hello"));
    }

    @Test
    void toReplace_nullString() {
        assertThat(str.toReplace(null, "a", "b"), is(""));
    }

    @Test
    void toReplace_nullTarget() {
        assertThat(str.toReplace("hello", null, "b"), is("hello"));
    }

    @Test
    void toReplace_nullReplacement() {
        assertThat(str.toReplace("hello", "l", null), is("hello"));
    }

    @Test
    void normalize_nfc() {
        // e + combining acute accent → precomposed é
        String input = "e\u0301";
        assertThat(str.normalize(input, "nfc"), is("\u00e9"));
    }

    @Test
    void normalize_nfd() {
        // precomposed é → e + combining acute accent
        String input = "\u00e9";
        assertThat(str.normalize(input, "nfd"), is("e\u0301"));
    }

    @Test
    void normalize_nfkc() {
        // fullwidth Latin small letter e → normal e
        String input = "\uff45";
        assertThat(str.normalize(input, "nfkc"), is("e"));
    }

    @Test
    void normalize_nfkd() {
        // fullwidth Latin small letter e → normal e
        String input = "\uff45";
        assertThat(str.normalize(input, "nfkd"), is("e"));
    }

    @Test
    void normalize_caseInsensitive() {
        String input = "e\u0301";
        assertThat(str.normalize(input, "NFC"), is("\u00e9"));
    }

    @Test
    void normalize_null() {
        assertThat(str.normalize(null, "nfc"), is(""));
    }

    @Test
    void normalize_nullForm() {
        assertThat(str.normalize("hello", null), is("hello"));
    }

    @Test
    void normalize_alreadyNormalized() {
        assertThat(str.normalize("hello", "nfc"), is("hello"));
    }
}
