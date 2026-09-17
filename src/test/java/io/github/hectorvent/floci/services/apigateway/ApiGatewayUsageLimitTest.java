package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code GetUsage}'s {@code limit} separates two things that are easy to conflate: whether a
 * request is accepted, and how large a page the response may carry.
 *
 * <p>Probed against real API Gateway, every value from 500 up to {@link Integer#MAX_VALUE} is
 * accepted, so none is rejected. That probe does not show the service ever returning more than 500
 * entries in one page, and the documented contract caps a page at 500, so the effective page size
 * stays capped even though the larger request is honoured.
 */
class ApiGatewayUsageLimitTest {

    @Test
    void anAbsentLimitUsesTheDocumentedDefault() {
        assertEquals(25, ApiGatewayService.resolveUsageLimit(null));
    }

    @Test
    void aLimitWithinTheDocumentedMaximumIsUsedAsGiven() {
        assertEquals(1, ApiGatewayService.resolveUsageLimit(1));
        assertEquals(100, ApiGatewayService.resolveUsageLimit(100));
        assertEquals(500, ApiGatewayService.resolveUsageLimit(500));
    }

    @Test
    void aLimitAboveTheMaximumIsAcceptedButThePageStaysCapped() {
        // Accepted, not rejected: real API Gateway accepts these.
        // Capped, not honoured verbatim: nothing shows it returning more than 500 in one page.
        assertEquals(500, ApiGatewayService.resolveUsageLimit(501));
        assertEquals(500, ApiGatewayService.resolveUsageLimit(100_000));
        assertEquals(500, ApiGatewayService.resolveUsageLimit(Integer.MAX_VALUE));
    }

    @Test
    void aLimitBelowOneIsRejected() {
        assertThrows(AwsException.class, () -> ApiGatewayService.resolveUsageLimit(0));
        assertThrows(AwsException.class, () -> ApiGatewayService.resolveUsageLimit(-1));
    }
}
