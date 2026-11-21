package io.floci.aws.services.apigateway;

import io.floci.aws.core.common.AwsException;
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
        assertEquals(25, ApiGatewayService.resolvePageSize(null));
    }

    @Test
    void aLimitWithinTheDocumentedMaximumIsUsedAsGiven() {
        assertEquals(1, ApiGatewayService.resolvePageSize(1));
        assertEquals(100, ApiGatewayService.resolvePageSize(100));
        assertEquals(500, ApiGatewayService.resolvePageSize(500));
    }

    @Test
    void aLimitAboveTheMaximumIsAcceptedButThePageStaysCapped() {
        // Accepted, not rejected: real API Gateway accepts these.
        // Capped, not honoured verbatim: nothing shows it returning more than 500 in one page.
        assertEquals(500, ApiGatewayService.resolvePageSize(501));
        assertEquals(500, ApiGatewayService.resolvePageSize(100_000));
        assertEquals(500, ApiGatewayService.resolvePageSize(Integer.MAX_VALUE));
    }

    @Test
    void aLimitBelowOneIsRejected() {
        assertThrows(AwsException.class, () -> ApiGatewayService.resolvePageSize(0));
        assertThrows(AwsException.class, () -> ApiGatewayService.resolvePageSize(-1));
    }
}
