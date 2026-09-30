package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaEsmFunctionResponseTypesValidationTest {

    private static Map<String, Object> request(Object functionResponseTypes) {
        Map<String, Object> req = new HashMap<>();
        req.put("FunctionResponseTypes", functionResponseTypes);
        return req;
    }

    private static AwsException assertRejected(Object functionResponseTypes) {
        AwsException ex = assertThrows(AwsException.class,
                () -> LambdaService.parseFunctionResponseTypes(request(functionResponseTypes)));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        return ex;
    }

    @Test
    void reportBatchItemFailuresIsAccepted() {
        assertEquals(List.of("ReportBatchItemFailures"),
                LambdaService.parseFunctionResponseTypes(request(List.of("ReportBatchItemFailures"))));
    }

    @Test
    void emptyListIsAccepted() {
        assertEquals(List.of(), LambdaService.parseFunctionResponseTypes(request(List.of())));
    }

    @Test
    void absentMemberParsesAsEmpty() {
        assertEquals(List.of(), LambdaService.parseFunctionResponseTypes(new HashMap<>()));
    }

    @Test
    void nullMemberParsesAsEmpty() {
        assertEquals(List.of(), LambdaService.parseFunctionResponseTypes(request(null)));
    }

    @Test
    void scalarMemberIsASerializationError() {
        AwsException ex = assertThrows(AwsException.class,
                () -> LambdaService.parseFunctionResponseTypes(request("ReportBatchItemFailures")));
        assertEquals("SerializationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void unknownValueIsRejected() {
        AwsException ex = assertRejected(List.of("ReportItemFailures"));
        assertTrue(ex.getMessage().contains("enum value set: [ReportBatchItemFailures]"), ex.getMessage());
    }

    @Test
    void nonStringValueIsRejected() {
        assertRejected(List.of(1));
    }

    @Test
    void moreThanOneItemIsRejected() {
        AwsException ex = assertRejected(List.of("ReportBatchItemFailures", "ReportBatchItemFailures"));
        assertTrue(ex.getMessage().contains("length less than or equal to 1"), ex.getMessage());
    }
}
