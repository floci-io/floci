package io.floci.aws.services.glue;

/**
 * How a crawl ended: its {@code LastCrawl} status, the table changes it made to the Data Catalog, and the
 * message {@code LastCrawl.ErrorMessage} carries when it failed.
 */
record CrawlOutcome(String status, int tablesCreated, int tablesUpdated, int tablesDeleted, String errorMessage) {

    static CrawlOutcome succeeded() {
        return succeeded(0, 0, 0);
    }

    static CrawlOutcome succeeded(int tablesCreated, int tablesUpdated, int tablesDeleted) {
        return new CrawlOutcome(GlueCrawlerRunService.STATUS_SUCCEEDED, tablesCreated, tablesUpdated,
                tablesDeleted, null);
    }

    static CrawlOutcome failed(String errorMessage) {
        return new CrawlOutcome(GlueCrawlerRunService.STATUS_FAILED, 0, 0, 0, errorMessage);
    }

    static CrawlOutcome cancelled() {
        return new CrawlOutcome(GlueCrawlerRunService.STATUS_CANCELLED, 0, 0, 0, null);
    }
}
