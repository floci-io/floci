package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers issue #1169: {@code metadata} is not an official DynamoDB reserved
 * word (the AWS list goes MERGE -> METHOD -> METRICS -> MIN), so {@code check}
 * must accept it as a bare attribute name.
 *
 * <p>RED/GREEN: re-adding {@code "METADATA"} to the {@code RESERVED} set makes
 * {@link #metadataIsNotReserved} fail (check throws). The two regression tests
 * guard that the validation mechanism itself is untouched.
 */
class DynamoDbReservedWordsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "UpdateExpression", "FilterExpression", "ConditionExpression", "ProjectionExpression"
    })
    void metadataIsNotReserved(String expressionType) {
        // Bare `metadata` attribute name, no `#` alias — must be accepted.
        assertDoesNotThrow(() -> DynamoDbReservedWords.check("SET metadata = :v", expressionType));
    }

    @Test
    void stillRejectsGenuineReservedWord() {
        // `NAME` remains an official reserved word — the check must still fire.
        AwsException ex = assertThrows(AwsException.class,
                () -> DynamoDbReservedWords.check("SET name = :v", "UpdateExpression"));
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("name"),
                "message should name the offending reserved word, got: " + ex.getMessage());
    }

    @Test
    void aliasedAttributeIsNeverReserved() {
        // A `#`-aliased name is opaque to the reserved-word check.
        assertDoesNotThrow(() -> DynamoDbReservedWords.check("SET #m = :v", "UpdateExpression"));
    }
}
