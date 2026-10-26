package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsException;

/**
 * The page request ListActivities and GetExecutionHistory check: a {@code maxResults} of 0 to 1000,
 * where 0 or none means 100, and a {@code nextToken} of 1 to 1024 characters. Callers read both
 * fields before calling it, so a wrongly typed field is reported before either bound.
 */
final class StepFunctionsPaging {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_TOKEN_LENGTH = 1024;

    private StepFunctionsPaging() {
    }

    /** Validates the pair and returns the number of items a page holds. */
    static int pageSize(Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 0 || maxResults > MAX_PAGE_SIZE)) {
            throw new AwsException("ValidationException", "maxResults must be between 0 and 1000.", 400);
        }
        if (nextToken != null && (nextToken.isEmpty() || nextToken.length() > MAX_TOKEN_LENGTH)) {
            throw new AwsException("ValidationException", "nextToken length must be between 1 and 1024.", 400);
        }
        return maxResults == null || maxResults == 0 ? DEFAULT_PAGE_SIZE : maxResults;
    }
}
