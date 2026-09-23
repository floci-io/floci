package io.github.hectorvent.floci.services.dynamodb;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every number below was measured on real DynamoDB in eu-west-2 on 2026-09-23.
 */
class DynamoDbVectorScoringTest {

    private static final float[] QUERY = {1f, 0f, 0f};

    @Test
    void scoresTheIdenticalVector() {
        float[] stored = {1f, 0f, 0f};
        assertEquals(0.0, DynamoDbVectorScoring.score("COSINE", QUERY, stored));
        assertEquals(0.0, DynamoDbVectorScoring.score("EUCLIDEAN", QUERY, stored));
        assertEquals(1.0, DynamoDbVectorScoring.score("DOT_PRODUCT", QUERY, stored));
    }

    @Test
    void scoresAUnitNormMix() {
        float[] stored = {0.6f, 0.8f, 0f};
        assertEquals(0.3999999761581421, DynamoDbVectorScoring.score("COSINE", QUERY, stored));
        assertEquals(0.8944271802902222, DynamoDbVectorScoring.score("EUCLIDEAN", QUERY, stored));
        assertEquals(0.6000000238418579, DynamoDbVectorScoring.score("DOT_PRODUCT", QUERY, stored));
    }

    @Test
    void scoresBeyondF32Precision() {
        float[] stored = {0.1f, 16777217f, 1.000000059604644775390625f};
        assertEquals(1.0, DynamoDbVectorScoring.score("COSINE", QUERY, stored));
        assertEquals(16777216.0, DynamoDbVectorScoring.score("EUCLIDEAN", QUERY, stored));
        assertEquals(0.10000000149011612, DynamoDbVectorScoring.score("DOT_PRODUCT", QUERY, stored));
    }

    /**
     * The exact sum is 16777223, which needs more than 24 bits. A double accumulator answers
     * 16777224, a sequential f32 loop answers 16777216 and a four-lane reduction answers
     * 16777222, so this case alone does not separate the last two.
     */
    @Test
    void sumsEightDimensionsAsAPairwiseTree() {
        float[] query = ones(8);
        float[] stored = ones(8);
        stored[0] = 16777216f;
        assertEquals(16777222f, DynamoDbVectorScoring.score("DOT_PRODUCT", query, stored));
    }

    /** At sixteen dimensions the four-lane reduction answers 16777228 and the tree 16777230. */
    @Test
    void sumsSixteenDimensionsAsAPairwiseTree() {
        float[] query = ones(16);
        float[] stored = ones(16);
        stored[0] = 16777216f;
        assertEquals(16777230f, DynamoDbVectorScoring.score("DOT_PRODUCT", query, stored));
    }

    @Test
    void dotProductIsTheOnlyFunctionWhereHigherIsCloser() {
        assertTrue(DynamoDbVectorScoring.higherIsCloser("DOT_PRODUCT"));
        assertFalse(DynamoDbVectorScoring.higherIsCloser("COSINE"));
        assertFalse(DynamoDbVectorScoring.higherIsCloser("EUCLIDEAN"));
    }

    @Test
    void rendersTheStoredFloatAsShortestPlainDecimal() {
        assertEquals("1.0", DynamoDbVectorScoring.render(1f));
        assertEquals("0.0", DynamoDbVectorScoring.render(0f));
        assertEquals("0.6", DynamoDbVectorScoring.render(0.6f));
        assertEquals("0.8", DynamoDbVectorScoring.render(0.8f));
        assertEquals("0.1", DynamoDbVectorScoring.render(0.1f));
        assertEquals("16777216.0", DynamoDbVectorScoring.render(16777217f));
        assertEquals("1.0", DynamoDbVectorScoring.render(1.000000059604644775390625f));
    }

    private static float[] ones(int dimensions) {
        float[] vector = new float[dimensions];
        Arrays.fill(vector, 1f);
        return vector;
    }
}
