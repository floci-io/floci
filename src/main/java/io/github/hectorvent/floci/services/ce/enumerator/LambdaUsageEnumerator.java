package io.github.hectorvent.floci.services.ce.enumerator;

import io.github.hectorvent.floci.core.common.ResourceUsageEnumerator;
import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Enumerates Lambda functions as Cost Explorer usage lines.
 * Floci does not measure real invocation count or duration in this PR; each
 * function emits a zero-quantity line so the function still surfaces in
 * {@code GetDimensionValues SERVICE} responses without billed cost. The shape
 * is in place for a follow-up PR that tracks invocations.
 */
@ApplicationScoped
public class LambdaUsageEnumerator implements ResourceUsageEnumerator {

    static final String SERVICE_CODE = "AWSLambda";

    private final LambdaService lambdaService;

    @Inject
    public LambdaUsageEnumerator(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public Stream<UsageLine> enumerate(Instant periodStart, Instant periodEnd, String region) {
        if (Duration.between(periodStart, periodEnd).toSeconds() <= 0) {
            return Stream.empty();
        }
        List<LambdaFunction> functions = lambdaService.listFunctions(region);
        Stream.Builder<UsageLine> out = Stream.builder();
        // Always emit a zero-quantity catalog line so the service surfaces in
        // GetDimensionValues SERVICE even when no functions exist.
        out.add(new UsageLine(periodStart, periodEnd,
                SERVICE_CODE, region,
                "AWS-Lambda-Requests", "Invoke",
                UsageLine.RECORD_TYPE_USAGE,
                "000000000000", null, new HashMap<>(),
                0.0, "Requests"));
        for (LambdaFunction fn : functions) {
            Map<String, String> tags = fn.getTags() == null ? new HashMap<>() : new HashMap<>(fn.getTags());
            String accountId = fn.getAccountId() != null ? fn.getAccountId() : "000000000000";
            out.add(new UsageLine(
                    periodStart, periodEnd,
                    SERVICE_CODE, region,
                    "AWS-Lambda-Requests", "Invoke",
                    UsageLine.RECORD_TYPE_USAGE,
                    accountId,
                    fn.getFunctionArn(),
                    tags,
                    0.0,
                    "Requests"));
        }
        return out.build();
    }
}
