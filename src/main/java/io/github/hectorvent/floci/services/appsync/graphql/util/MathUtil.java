package io.github.hectorvent.floci.services.appsync.graphql.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

public class MathUtil {

    public Integer roundNum(Double value) {
        if (value == null) return 0;
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    public Double minVal(Double a, Double b) {
        if (a == null) return b != null ? b : 0.0;
        if (b == null) return a;
        return Math.min(a, b);
    }

    public Double maxVal(Double a, Double b) {
        if (a == null) return b != null ? b : 0.0;
        if (b == null) return a;
        return Math.max(a, b);
    }

    public Double randomDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }

    public Integer randomWithinRange(Integer min, Integer max) {
        if (min == null || max == null) return 0;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
