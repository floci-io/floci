package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AwaitilityDefaultsIntegrationTest {

    @Test
    void awaitInAQuarkusTestChecksAHeldConditionWithoutSleepingFirst() {
        AwaitilityDefaultsTest.assertFirstCheckIsImmediate();
    }
}
