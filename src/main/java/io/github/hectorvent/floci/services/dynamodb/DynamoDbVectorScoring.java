package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigDecimal;

/**
 * The numeric core of SearchVectors: the three distance functions and the f32 rendering of a
 * stored vector.
 *
 * <p>AWS computes a score entirely in 32-bit floating point and sums the per-dimension terms with
 * a pairwise binary-tree reduction. Measured on real DynamoDB in eu-west-2 (2026-09-23): the dot
 * product of {@code [16777216,1,1,1,1,1,1,1]} with eight ones answers 16777222, and the same
 * shape at sixteen dimensions answers 16777230. A double accumulator answers 16777224 and
 * 16777232, a sequential f32 loop answers 16777216 twice, and a four-lane reduction answers
 * 16777222 and 16777228, so only the tree reproduces both.
 */
final class DynamoDbVectorScoring {

    static final String COSINE = "COSINE";
    static final String EUCLIDEAN = "EUCLIDEAN";
    static final String DOT_PRODUCT = "DOT_PRODUCT";

    private DynamoDbVectorScoring() {}

    /** The score of one stored vector against the query vector, widened for JSON. */
    static double score(String distanceFunction, float[] query, float[] stored) {
        return switch (distanceFunction) {
            case COSINE -> cosine(query, stored);
            case EUCLIDEAN -> euclidean(query, stored);
            case DOT_PRODUCT -> dotProduct(query, stored);
            // AWS rejects any other value at CreateTable, so no index should carry one.
            default -> throw new AwsException("ValidationException",
                    "Unsupported distance function: " + distanceFunction, 400);
        };
    }

    /** Whether the higher score is the closer match, which only DOT_PRODUCT reports. */
    static boolean higherIsCloser(String distanceFunction) {
        return DOT_PRODUCT.equals(distanceFunction);
    }

    private static float cosine(float[] query, float[] stored) {
        float dot = dotProduct(query, stored);
        double denominator = Math.sqrt(selfDot(query)) * Math.sqrt(selfDot(stored));
        if (denominator == 0.0) {
            // A zero vector has no direction. Scoring it 1 keeps the response valid JSON,
            // which the NaN of a 0/0 division would not.
            return 1.0f;
        }
        return (float) (1.0 - dot / denominator);
    }

    private static float euclidean(float[] query, float[] stored) {
        float[] terms = new float[query.length];
        for (int i = 0; i < query.length; i++) {
            float difference = query[i] - stored[i];
            terms[i] = difference * difference;
        }
        return (float) Math.sqrt(treeSum(terms, terms.length));
    }

    private static float dotProduct(float[] query, float[] stored) {
        float[] terms = new float[query.length];
        for (int i = 0; i < query.length; i++) {
            terms[i] = query[i] * stored[i];
        }
        return treeSum(terms, terms.length);
    }

    private static float selfDot(float[] vector) {
        return dotProduct(vector, vector);
    }

    /** Sums the terms by repeated pairwise halving, all arithmetic in float. Mutates {@code terms}. */
    private static float treeSum(float[] terms, int length) {
        int n = length;
        while (n > 1) {
            int half = (n + 1) / 2;
            for (int i = 0; i + 1 < n; i += 2) {
                terms[i / 2] = terms[i] + terms[i + 1];
            }
            if ((n & 1) == 1) {
                terms[half - 1] = terms[n - 1];
            }
            n = half;
        }
        return n == 0 ? 0f : terms[0];
    }

    /**
     * A stored f32 value as AWS writes it back: the shortest decimal naming that float, in plain
     * notation, always with a fractional part. Measured on real DynamoDB (eu-west-2, 2026-09-23),
     * where 1 comes back as {@code 1.0} and 16777217 as {@code 16777216.0}.
     */
    static String render(float value) {
        String plain = new BigDecimal(Float.toString(value)).toPlainString();
        return plain.indexOf('.') < 0 ? plain + ".0" : plain;
    }
}
