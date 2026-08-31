package io.github.hectorvent.floci.services.stepfunctions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Every expression here was run through
 * {@code aws stepfunctions validate-state-machine-definition --region us-east-1} as the Output of
 * a one-state Pass machine. The names asserted are the ones AWS puts in
 * {@code Reference to '<name>' at the top level is not supported.}, the expressions asserted
 * empty are the ones AWS answers {@code result: OK}, and the parse errors asserted are the
 * messages AWS puts in its {@code INVALID_JSONATA_EXPRESSION} diagnostic, word for word.
 */
class JsonataTopLevelReferencesTest {

    private static List<String> references(String expression) {
        return JsonataTopLevelReferences.analyze(expression).topLevelReferences();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "phone",
            "phone[0].number",
            "phone.number.street",
            "-phone",
            "phone ? 1 : 2",
            "$states.input.a ? phone : 2",
            "phone & 'x'",
            "phone in $states.input.b",
            "$sum(phone)",
            "phone(1)",
            "phone ~> $string()",
            "[1..phone]",
            "(phone)",
            "(phone).other",
            "[phone].other",
            "[phone, 1]",
            "{phone: 1}",
            "($x := phone; $x)",
            "$map($states.input.a, function($y){ phone })",
            "$count(phone[inner])",
            "phone.**.deeper"})
    @DisplayName("AWS names the identifier read at the top level")
    void namesTheTopLevelIdentifier(String expression) {
        assertEquals(List.of("phone"), references(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "$states.input.phone",
            "$states.input.a.phone",
            "$states.input.a[0].phone",
            "$states.input.a.phone[0]",
            "$states.input.items[phone > 3]",
            "$states.input.a^(phone)",
            "$states.input.a{'k': phone}",
            "$states.input.a.(phone)",
            "$states.input.a.$phone",
            "$phone",
            "$states.input.a ~> $string()",
            "$states.input ~> |phone|{'b': 1}|",
            "**.phone",
            "{'k': 1}.phone",
            "$states.input.a[0][phone]",
            "$states.input.a[$ > 1]",
            "function($x){ $x.phone }",
            "$map($states.input.a, function($x){ $x.phone })",
            "'phone'",
            "1 + 1",
            "$now()"})
    @DisplayName("AWS accepts a name that is not read from the top-level context")
    void namesNothingWhenTheReferenceIsAnchored(String expression) {
        assertEquals(List.of(), references(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "$.phone", "$[0]", "$string($)",
            "$map($states.input.a, function($x){ $ })"})
    @DisplayName("the context item itself is named '$', as AWS names it")
    void namesTheContextItem(String expression) {
        assertEquals(List.of("$"), references(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$$", "$$.phone"})
    @DisplayName("'$$' is named on its own, as AWS's separate rule needs")
    void namesTheRootReference(String expression) {
        assertEquals(List.of("$$"), references(expression));
    }

    @Test
    @DisplayName("every distinct name is reported once, in writing order")
    void reportsEveryDistinctNameOnce() {
        assertEquals(List.of("aaa", "bbb"), references("aaa + bbb"));
        assertEquals(List.of("bbb", "aaa"), references("[bbb, aaa]"));
        assertEquals(List.of("aaa"), references("aaa + aaa"));
        assertEquals(List.of("aaa", "bbb"), references("(aaa; bbb)"));
        assertEquals(List.of("aaa", "bbb"), references("$number(aaa) + $number(bbb)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "$states.errorOutput",
            "$states.errorOutput.Cause",
            "$states.errorOutput.Error",
            "[$states.errorOutput]",
            "$states.errorOutput ? 1 : 2"})
    @DisplayName("'$states.errorOutput' is named on its own; whether it is allowed is the caller's call")
    void namesTheStatesErrorOutputPath(String expression) {
        assertEquals(List.of("$states.errorOutput"), references(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$states.input.errorOutput", "$states.error", "$states"})
    @DisplayName("only the exact '$states.errorOutput' path is named")
    void namesNothingForALookalikeStatesPath(String expression) {
        assertEquals(List.of(), references(expression));
    }

    @Test
    @DisplayName("a syntax error names nothing but reports the message AWS prints for it")
    void reportsTheParserMessageWhenTheExpressionDoesNotParse() {
        assertParseError("phone[1,2)", "Expected \"]\", got \",\"");
        assertParseError("phone %.other", "The symbol \".\" cannot be used as a unary operator");
        assertParseError("", "Unexpected end of expression");
    }

    private static void assertParseError(String expression, String expectedMessage) {
        JsonataTopLevelReferences.Analysis analysis = JsonataTopLevelReferences.analyze(expression);
        assertEquals(List.of(), analysis.topLevelReferences());
        assertEquals(expectedMessage, analysis.parseError());
    }

    @Test
    @DisplayName("an expression that parses carries no parse error")
    void carriesNoParseErrorWhenTheExpressionParses() {
        assertNull(JsonataTopLevelReferences.analyze("phone").parseError());
    }
}
