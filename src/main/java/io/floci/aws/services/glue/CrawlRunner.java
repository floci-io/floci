package io.floci.aws.services.glue;

/**
 * Runs a crawl of the named crawler's targets and reports how it ended. The run service calls it when a
 * crawl settles; a crawl that reads no data store ends as {@link CrawlOutcome#succeeded()} with no table changes.
 */
@FunctionalInterface
interface CrawlRunner {

    CrawlOutcome run(String crawlerName);

    /** Crawls nothing: every crawl succeeds without touching the Data Catalog. */
    CrawlRunner NO_DATA_STORE = crawlerName -> CrawlOutcome.succeeded();
}
