package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfnDeletesTest {

    @Test
    void runsTheDelete() {
        AtomicBoolean deleted = new AtomicBoolean();

        CfnDeletes.safeDelete("queue", "q1", () -> deleted.set(true), "NoSuchEntity");

        assertTrue(deleted.get());
    }

    @Test
    void toleratesTheNamedAlreadyGoneCode() {
        assertDoesNotThrow(() -> CfnDeletes.safeDelete("DB proxy", "proxy-1", () -> {
            throw new AwsException("DBProxyNotFoundFault", "not found", 404);
        }, "DBProxyNotFoundFault"));
    }

    /**
     * The reason the tolerated codes are explicit. A stack deletion reports DELETE_FAILED when a
     * delete throws, which is how a genuine failure such as a non-empty bucket surfaces; swallowing
     * everything would report a clean deletion while the resource is still there.
     */
    @Test
    void propagatesAnyOtherFailure() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> CfnDeletes.safeDelete("bucket", "my-bucket", () -> {
                    throw new AwsException("BucketNotEmpty", "The bucket you tried to delete is not empty", 409);
                }, "NoSuchBucket"));

        assertEquals("BucketNotEmpty", thrown.getErrorCode());
    }

    @Test
    void toleratesNothingWhenNoCodesAreGiven() {
        assertThrows(AwsException.class, () -> CfnDeletes.safeDelete("topic", "t1", () -> {
            throw new AwsException("NotFound", "gone", 404);
        }));
    }

    @Test
    void acceptsSeveralAlreadyGoneCodes() {
        assertDoesNotThrow(() -> CfnDeletes.safeDelete("target group", "tg-1", () -> {
            throw new AwsException("DBProxyTargetGroupNotFoundFault", "not found", 404);
        }, "DBProxyNotFoundFault", "DBProxyTargetGroupNotFoundFault"));
    }
}
