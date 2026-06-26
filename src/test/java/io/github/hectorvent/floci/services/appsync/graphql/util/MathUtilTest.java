package io.github.hectorvent.floci.services.appsync.graphql.util;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MathUtilTest {

    private final MathUtil math = new MathUtil();

    @Test
    void roundNum_basic() {
        assertThat(math.roundNum(2.3), is(2));
        assertThat(math.roundNum(2.5), is(3));
        assertThat(math.roundNum(2.7), is(3));
    }

    @Test
    void roundNum_negative() {
        assertThat(math.roundNum(-2.3), is(-2));
        assertThat(math.roundNum(-2.5), is(-3));
    }

    @Test
    void roundNum_null() {
        assertThat(math.roundNum(null), is(0));
    }

    @Test
    void roundNum_integer() {
        assertThat(math.roundNum(3.0), is(3));
    }

    @Test
    void minVal_basic() {
        assertThat(math.minVal(3.0, 1.0), is(1.0));
        assertThat(math.minVal(1.0, 3.0), is(1.0));
    }

    @Test
    void minVal_equal() {
        assertThat(math.minVal(2.0, 2.0), is(2.0));
    }

    @Test
    void minVal_negative() {
        assertThat(math.minVal(-1.0, -3.0), is(-3.0));
    }

    @Test
    void minVal_nullFirst() {
        assertThat(math.minVal(null, 5.0), is(5.0));
    }

    @Test
    void minVal_nullSecond() {
        assertThat(math.minVal(5.0, null), is(5.0));
    }

    @Test
    void minVal_bothNull() {
        assertThat(math.minVal(null, null), is(0.0));
    }

    @Test
    void maxVal_basic() {
        assertThat(math.maxVal(3.0, 1.0), is(3.0));
        assertThat(math.maxVal(1.0, 3.0), is(3.0));
    }

    @Test
    void maxVal_equal() {
        assertThat(math.maxVal(2.0, 2.0), is(2.0));
    }

    @Test
    void maxVal_negative() {
        assertThat(math.maxVal(-1.0, -3.0), is(-1.0));
    }

    @Test
    void maxVal_nullFirst() {
        assertThat(math.maxVal(null, 5.0), is(5.0));
    }

    @Test
    void maxVal_nullSecond() {
        assertThat(math.maxVal(5.0, null), is(5.0));
    }

    @Test
    void maxVal_bothNull() {
        assertThat(math.maxVal(null, null), is(0.0));
    }

    @Test
    void randomDouble_range() {
        double result = math.randomDouble();
        assertThat(result, greaterThanOrEqualTo(0.0));
        assertThat(result, lessThan(1.0));
    }

    @Test
    void randomWithinRange_range() {
        for (int i = 0; i < 100; i++) {
            int result = math.randomWithinRange(5, 10);
            assertThat(result, greaterThanOrEqualTo(5));
            assertThat(result, lessThanOrEqualTo(10));
        }
    }

    @Test
    void randomWithinRange_null() {
        assertThat(math.randomWithinRange(null, 10), is(0));
        assertThat(math.randomWithinRange(5, null), is(0));
    }
}
