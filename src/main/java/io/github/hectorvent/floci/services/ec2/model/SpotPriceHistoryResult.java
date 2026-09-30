package io.github.hectorvent.floci.services.ec2.model;

import java.util.List;

/**
 * Result of a paginated DescribeSpotPriceHistory call.
 *
 * @param spotPrices the spot prices for this page
 * @param nextToken  token for the next page, or null when the page is the last
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeSpotPriceHistory.html">AWS EC2 DescribeSpotPriceHistory</a>
 */
public record SpotPriceHistoryResult(
        List<SpotPrice> spotPrices,
        String nextToken
) {}
