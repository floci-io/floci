package io.github.hectorvent.floci.services.stepfunctions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every expression here was run through
 * {@code aws stepfunctions validate-state-machine-definition --region us-east-1} as the Output of
 * a one-state Pass machine. The names asserted are the ones AWS puts in
 * {@code Reference to '<name>' at the top level is not supported.}, and the expressions asserted
 * empty are the ones AWS answers {@code result: OK}.
 */
class JsonataTopLevelReferencesTest {

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
        assertEquals(List.of("phone"), JsonataTopLevelReferences.in(expression));
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
        assertEquals(List.of(), JsonataTopLevelReferences.in(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "$.phone", "$[0]", "$string($)",
            "$map($states.input.a, function($x){ $ })"})
    @DisplayName("the context item itself is named '$', as AWS names it")
    void namesTheContextItem(String expression) {
        assertEquals(List.of("$"), JsonataTopLevelReferences.in(expression));
    }

    @Test
    @DisplayName("'$$' is AWS's separate rule and is left to it")
    void leavesTheRootReferenceAlone() {
        assertEquals(List.of(), JsonataTopLevelReferences.in("$$"));
        assertEquals(List.of(), JsonataTopLevelReferences.in("$$.phone"));
    }

    @Test
    @DisplayName("every distinct name is reported once, in writing order")
    void reportsEveryDistinctNameOnce() {
        assertEquals(List.of("aaa", "bbb"), JsonataTopLevelReferences.in("aaa + bbb"));
        assertEquals(List.of("bbb", "aaa"), JsonataTopLevelReferences.in("[bbb, aaa]"));
        assertEquals(List.of("aaa"), JsonataTopLevelReferences.in("aaa + aaa"));
        assertEquals(List.of("aaa", "bbb"), JsonataTopLevelReferences.in("(aaa; bbb)"));
        assertEquals(List.of("aaa", "bbb"),
                JsonataTopLevelReferences.in("$number(aaa) + $number(bbb)"));
    }

    @Test
    @DisplayName("a syntax error names nothing, so the definition stays accepted")
    void namesNothingWhenTheExpressionDoesNotParse() {
        assertEquals(List.of(), JsonataTopLevelReferences.in("phone[1,2)"));
        assertEquals(List.of(), JsonataTopLevelReferences.in("phone %.other"));
        assertEquals(List.of(), JsonataTopLevelReferences.in(""));
    }
}
