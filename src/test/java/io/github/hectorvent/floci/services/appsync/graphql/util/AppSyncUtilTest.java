package io.github.hectorvent.floci.services.appsync.graphql.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AppSyncUtilTest {

    private final AppSyncUtil util = new AppSyncUtil(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void escapeJavaScript_null() {
        assertThat(util.escapeJavaScript(null), is(""));
    }

    @Test
    void escapeJavaScript_empty() {
        assertThat(util.escapeJavaScript(""), is(""));
    }

    @Test
    void escapeJavaScript_plain() {
        assertThat(util.escapeJavaScript("hello"), is("hello"));
    }

    @Test
    void escapeJavaScript_quotes() {
        assertThat(util.escapeJavaScript("a\"b"), is("a\\\"b"));
        assertThat(util.escapeJavaScript("a'b"), is("a\\'b"));
    }

    @Test
    void escapeJavaScript_backslash() {
        assertThat(util.escapeJavaScript("a\\b"), is("a\\\\b"));
    }

    @Test
    void escapeJavaScript_controlChars() {
        assertThat(util.escapeJavaScript("a\tb"), is("a\\tb"));
        assertThat(util.escapeJavaScript("a\nb"), is("a\\nb"));
        assertThat(util.escapeJavaScript("a\rb"), is("a\\rb"));
        assertThat(util.escapeJavaScript("a\bb"), is("a\\bb"));
        assertThat(util.escapeJavaScript("a\fb"), is("a\\fb"));
    }

    @Test
    void escapeJavaScript_unicode() {
        assertThat(util.escapeJavaScript("caf\u00e9"), containsString("\\u"));
    }

    @Test
    void escapeJavaScript_slash() {
        assertThat(util.escapeJavaScript("a/b"), is("a\\/b"));
    }

    @Test
    void urlEncode_null() {
        assertThat(util.urlEncode(null), is(""));
    }

    @Test
    void urlEncode_empty() {
        assertThat(util.urlEncode(""), is(""));
    }

    @Test
    void urlEncode_spaces() {
        assertThat(util.urlEncode("hello world"), is("hello+world"));
    }

    @Test
    void urlEncode_specialChars() {
        assertThat(util.urlEncode("a&b=c"), is("a%26b%3Dc"));
    }

    @Test
    void urlDecode_null() {
        assertThat(util.urlDecode(null), is(""));
    }

    @Test
    void urlDecode_empty() {
        assertThat(util.urlDecode(""), is(""));
    }

    @Test
    void urlDecode_spaces() {
        assertThat(util.urlDecode("hello+world"), is("hello world"));
    }

    @Test
    void urlDecode_percentEncoded() {
        assertThat(util.urlDecode("a%26b"), is("a&b"));
    }

    @Test
    void urlEncode_roundtrip() {
        String original = "hello world! @#$%";
        assertThat(util.urlDecode(util.urlEncode(original)), is(original));
    }

    @Test
    void base64Encode_null() {
        assertThat(util.base64Encode(null), is(""));
    }

    @Test
    void base64Encode_hello() {
        assertThat(util.base64Encode("hello".getBytes(StandardCharsets.UTF_8)), is("aGVsbG8="));
    }

    @Test
    void base64Decode_null() {
        assertThat(util.base64Decode(null), is(""));
    }

    @Test
    void base64Decode_hello() {
        assertThat(util.base64Decode("aGVsbG8="), is("hello"));
    }

    @Test
    void base64_roundtrip() {
        String original = "test string with unicode: \u00e9";
        assertThat(util.base64Decode(util.base64Encode(original.getBytes(StandardCharsets.UTF_8))), is(original));
    }

    @Test
    void parseJson_null() {
        Object result = util.parseJson(null);
        assertThat(result, instanceOf(Map.class));
        assertThat(((Map<?, ?>) result).isEmpty(), is(true));
    }

    @Test
    void parseJson_empty() {
        Object result = util.parseJson("");
        assertThat(result, instanceOf(Map.class));
        assertThat(((Map<?, ?>) result).isEmpty(), is(true));
    }

    @Test
    void parseJson_invalid() {
        Object result = util.parseJson("not json");
        assertThat(result, instanceOf(Map.class));
        assertThat(((Map<?, ?>) result).isEmpty(), is(true));
    }

    @Test
    void parseJson_object() {
        Object result = util.parseJson("{\"a\":1,\"b\":\"hello\"}");
        assertThat(result, instanceOf(Map.class));
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.get("a"), is(1));
        assertThat(map.get("b"), is("hello"));
    }

    @Test
    void parseJson_array() {
        Object result = util.parseJson("[1,2,3]");
        assertThat(result, instanceOf(List.class));
        List<?> list = (List<?>) result;
        assertThat(list, hasSize(3));
    }

    @Test
    void toJson_null() {
        assertThat(util.toJson(null), is("null"));
    }

    @Test
    void toJson_string() {
        assertThat(util.toJson("hello"), is("\"hello\""));
    }

    @Test
    void toJson_map() {
        String json = util.toJson(Map.of("a", 1));
        assertThat(json, containsString("\"a\""));
        assertThat(json, containsString("1"));
    }

    @Test
    void toJson_list() {
        String json = util.toJson(List.of(1, 2, 3));
        assertThat(json, is("[1,2,3]"));
    }

    @Test
    void autoId_format() {
        String id = util.autoId();
        assertThat(id, matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    void autoId_uniqueness() {
        String id1 = util.autoId();
        String id2 = util.autoId();
        assertThat(id1, is(not(id2)));
    }

    @Test
    void quiet_any() {
        assertThat(util.quiet("hello"), is(""));
        assertThat(util.quiet(null), is(""));
        assertThat(util.quiet(""), is(""));
    }

    @Test
    void qr_any() {
        assertThat(util.qr("hello"), is(""));
        assertThat(util.qr(null), is(""));
        assertThat(util.qr(""), is(""));
    }

    @Test
    void matches_match() {
        assertThat(util.matches("[a-z]+", "hello"), is(true));
    }

    @Test
    void matches_noMatch() {
        assertThat(util.matches("[a-z]+", "HELLO"), is(false));
    }

    @Test
    void matches_nullPattern() {
        assertThat(util.matches(null, "hello"), is(false));
    }

    @Test
    void matches_nullValue() {
        assertThat(util.matches("[a-z]+", null), is(false));
    }

    @Test
    void defaultIfNull_null() {
        assertThat(util.defaultIfNull(null, "default"), is("default"));
    }

    @Test
    void defaultIfNull_nonNull() {
        assertThat(util.defaultIfNull("value", "default"), is("value"));
    }

    @Test
    void defaultIfNull_bothNull() {
        assertThat(util.defaultIfNull(null, null), is(nullValue()));
    }

    @Test
    void defaultIfNullOrEmpty_null() {
        assertThat(util.defaultIfNullOrEmpty(null, "default"), is("default"));
    }

    @Test
    void defaultIfNullOrEmpty_empty() {
        assertThat(util.defaultIfNullOrEmpty("", "default"), is("default"));
    }

    @Test
    void defaultIfNullOrEmpty_nonEmpty() {
        assertThat(util.defaultIfNullOrEmpty("value", "default"), is("value"));
    }

    @Test
    void defaultIfNullOrEmpty_whitespace() {
        assertThat(util.defaultIfNullOrEmpty(" ", "default"), is(" "));
    }

    @Test
    void defaultIfNullOrBlank_null() {
        assertThat(util.defaultIfNullOrBlank(null, "default"), is("default"));
    }

    @Test
    void defaultIfNullOrBlank_empty() {
        assertThat(util.defaultIfNullOrBlank("", "default"), is("default"));
    }

    @Test
    void defaultIfNullOrBlank_whitespace() {
        assertThat(util.defaultIfNullOrBlank("  ", "default"), is("default"));
    }

    @Test
    void defaultIfNullOrBlank_nonBlank() {
        assertThat(util.defaultIfNullOrBlank("value", "default"), is("value"));
    }

    @Test
    void isNull_null() {
        assertThat(util.isNull(null), is(true));
    }

    @Test
    void isNull_nonNull() {
        assertThat(util.isNull("value"), is(false));
    }

    @Test
    void isNullOrEmpty_null() {
        assertThat(util.isNullOrEmpty(null), is(true));
    }

    @Test
    void isNullOrEmpty_empty() {
        assertThat(util.isNullOrEmpty(""), is(true));
    }

    @Test
    void isNullOrEmpty_nonEmpty() {
        assertThat(util.isNullOrEmpty("a"), is(false));
    }

    @Test
    void isNullOrBlank_null() {
        assertThat(util.isNullOrBlank(null), is(true));
    }

    @Test
    void isNullOrBlank_empty() {
        assertThat(util.isNullOrBlank(""), is(true));
    }

    @Test
    void isNullOrBlank_whitespace() {
        assertThat(util.isNullOrBlank("  "), is(true));
    }

    @Test
    void isNullOrBlank_nonBlank() {
        assertThat(util.isNullOrBlank("a"), is(false));
    }

    @Test
    void isString_string() {
        assertThat(util.isString("hello"), is(true));
    }

    @Test
    void isString_nonString() {
        assertThat(util.isString(123), is(false));
    }

    @Test
    void isNumber_number() {
        assertThat(util.isNumber(123), is(true));
        assertThat(util.isNumber(1.5), is(true));
    }

    @Test
    void isNumber_nonNumber() {
        assertThat(util.isNumber("hello"), is(false));
    }

    @Test
    void isBoolean_boolean() {
        assertThat(util.isBoolean(true), is(true));
    }

    @Test
    void isBoolean_nonBoolean() {
        assertThat(util.isBoolean("true"), is(false));
    }

    @Test
    void isList_list() {
        assertThat(util.isList(List.of()), is(true));
    }

    @Test
    void isList_nonList() {
        assertThat(util.isList("[]"), is(false));
    }

    @Test
    void isMap_map() {
        assertThat(util.isMap(Map.of()), is(true));
    }

    @Test
    void isMap_nonMap() {
        assertThat(util.isMap("{}"), is(false));
    }

    @Test
    void typeOf_null() {
        assertThat(util.typeOf(null), is("Null"));
    }

    @Test
    void typeOf_string() {
        assertThat(util.typeOf("hello"), is("String"));
    }

    @Test
    void typeOf_number() {
        assertThat(util.typeOf(42), is("Number"));
        assertThat(util.typeOf(3.14), is("Number"));
        assertThat(util.typeOf(123L), is("Number"));
    }

    @Test
    void typeOf_boolean() {
        assertThat(util.typeOf(true), is("Boolean"));
    }

    @Test
    void typeOf_list() {
        assertThat(util.typeOf(List.of()), is("List"));
    }

    @Test
    void typeOf_map() {
        assertThat(util.typeOf(Map.of()), is("Map"));
    }

    @Test
    void typeOf_unknown() {
        assertThat(util.typeOf(new Object()), is("Object"));
    }

    @Test
    void error_singleArg() {
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, () -> util.error("msg"));
        assertThat(ex.getMessage(), is("msg"));
        assertThat(ex.getErrorType(), is("Unknown"));
        assertThat(ex.getData(), is(nullValue()));
        assertThat(ex.getErrorInfo(), is(nullValue()));
    }

    @Test
    void error_twoArgs() {
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, () -> util.error("msg", "Custom"));
        assertThat(ex.getMessage(), is("msg"));
        assertThat(ex.getErrorType(), is("Custom"));
    }

    @Test
    void error_threeArgs() {
        Object data = Map.of("key", "value");
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, () -> util.error("msg", "Type", data));
        assertThat(ex.getData(), is(data));
        assertThat(ex.getErrorInfo(), is(nullValue()));
    }

    @Test
    void error_fourArgs() {
        Object data = Map.of("key", "value");
        Object info = Map.of("info", "detail");
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, () -> util.error("msg", "Type", data, info));
        assertThat(ex.getData(), is(data));
        assertThat(ex.getErrorInfo(), is(info));
    }

    @Test
    void unauthorized() {
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, util::unauthorized);
        assertThat(ex.getMessage(), is("Not Authorized"));
        assertThat(ex.getErrorType(), is("Unauthorized"));
    }

    @Test
    void validate_true() {
        assertDoesNotThrow(() -> util.validate(true, "msg"));
    }

    @Test
    void validate_false() {
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, () -> util.validate(false, "msg"));
        assertThat(ex.getMessage(), is("msg"));
        assertThat(ex.getErrorType(), is("Unknown"));
    }

    @Test
    void validate_false_withType() {
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, () -> util.validate(false, "msg", "Custom"));
        assertThat(ex.getErrorType(), is("Custom"));
    }

    @Test
    void validate_false_withData() {
        Object data = Map.of("k", "v");
        VtlErrorSignal ex = assertThrows(VtlErrorSignal.class, () -> util.validate(false, "msg", "Type", data));
        assertThat(ex.getData(), is(data));
    }

    @Test
    void appendError_noException() {
        assertDoesNotThrow(() -> util.appendError("msg"));
        assertDoesNotThrow(() -> util.appendError("msg", "Type"));
        assertDoesNotThrow(() -> util.appendError("msg", "Type", Map.of()));
        assertDoesNotThrow(() -> util.appendError("msg", "Type", Map.of(), Map.of()));
    }
}
