package io.floci.aws.services.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;

final class DynamoDbTimestamps {

    private DynamoDbTimestamps() {}

    static BigDecimal epochSeconds(Instant instant) {
        return BigDecimal.valueOf(instant.toEpochMilli(), 3);
    }

    static Instant instantOf(BigDecimal epochSeconds) {
        return Instant.ofEpochMilli(epochSeconds.movePointRight(3).longValue());
    }
}
