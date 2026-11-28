package io.floci.aws.services.glue;

/**
 * Runs a crawl of the named crawler's targets and reports how it ended. The run service calls it when a
 * crawl settles; a crawl that reads no data store ends as {@link CrawlOutcome#succeeded()} with no table changes.
 *
 * <p>A crawl that reads a data store over the network splits in two: {@link #read} reads the store and may
 * block, so the run service calls it in the background, outside its lock; the {@link CatalogWrite} it returns
 * writes the Data Catalog and runs under the lock, only while the crawl is still the crawler's current one, so a
 * stopped crawl writes nothing.
 */
@FunctionalInterface
interface CrawlRunner {

    CrawlOutcome run(String crawlerName);

    /** Whether a crawl of the crawler reads a data store, so the run service reads it in the background. */
    default boolean readsDataStore(String crawlerName) {
        return false;
    }

    /** Reads the crawler's data stores and returns the step that writes what was found. */
    default CatalogWrite read(String crawlerName) {
        return () -> run(crawlerName);
    }

    /** Writes what a crawl found to the Data Catalog and reports how the crawl ended. */
    @FunctionalInterface
    interface CatalogWrite {
        CrawlOutcome apply();
    }

    /** Crawls nothing: every crawl succeeds without touching the Data Catalog. */
    CrawlRunner NO_DATA_STORE = crawlerName -> CrawlOutcome.succeeded();
}
