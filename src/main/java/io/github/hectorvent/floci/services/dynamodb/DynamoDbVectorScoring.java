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

    // The band AWS writes in plain notation. Outside it the value comes back in scientific form.
    private static final BigDecimal PLAIN_LOWER_BOUND = new BigDecimal("0.000001");
    private static final BigDecimal PLAIN_UPPER_BOUND = new BigDecimal("1E+13");

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
            // CreateTable and UpdateTable reject an unknown value, so only a missing one reaches
            // here: answer it rather than letting a null selector throw.
            case null, default -> throw new AwsException("ValidationException",
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
     * A stored f32 value as AWS writes it back: the shortest decimal naming that float. Plain
     * notation with at least one fractional digit inside {@code [1e-6, 1e13)}, and
     * {@code <mantissa>e<exponent>} outside it, with a lowercase e, no plus and no zero padding.
     *
     * <p>Both boundaries were measured on real DynamoDB (eu-west-2, 2026-09-23): 9.9e-7 comes back
     * scientific and 1e-6 plain, 9e12 comes back plain and 1e13 scientific. 1 comes back as
     * {@code 1.0} and 16777217 as {@code 16777216.0}.
     *
     * <p>{@code Float.toString} supplies the shortest round-tripping decimal, which it is for every
     * normal float. Subnormals below {@link Float#MIN_NORMAL} are the one exception: AWS writes
     * {@code Float.MIN_VALUE} as {@code 1e-45} where this renders {@code 1.4e-45}.
     */
    static String render(float value) {
        if (value == 0f) {
            return "0.0";
        }
        BigDecimal shortest = new BigDecimal(Float.toString(value)).stripTrailingZeros();
        BigDecimal magnitude = shortest.abs();
        if (magnitude.compareTo(PLAIN_LOWER_BOUND) >= 0 && magnitude.compareTo(PLAIN_UPPER_BOUND) < 0) {
            String plain = shortest.toPlainString();
            return plain.indexOf('.') < 0 ? plain + ".0" : plain;
        }
        return scientific(shortest);
    }

    /**
     * The mantissa carries the shortest digits with no trailing {@code .0}, so 1e13 is written
     * {@code 1e13} rather than {@code 1.0e13}.
     */
    private static String scientific(BigDecimal stripped) {
        String digits = stripped.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - stripped.scale();
        StringBuilder out = new StringBuilder();
        if (stripped.signum() < 0) {
            out.append('-');
        }
        out.append(digits.charAt(0));
        if (digits.length() > 1) {
            out.append('.').append(digits, 1, digits.length());
        }
        return out.append('e').append(exponent).toString();
    }
}
