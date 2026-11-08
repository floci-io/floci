package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BatchGetCrawlersResponse;
import software.amazon.awssdk.services.glue.model.Crawler;
import software.amazon.awssdk.services.glue.model.CrawlerMetrics;
import software.amazon.awssdk.services.glue.model.CrawlerNotRunningException;
import software.amazon.awssdk.services.glue.model.CrawlerState;
import software.amazon.awssdk.services.glue.model.LastCrawlStatus;
import software.amazon.awssdk.services.glue.model.ScheduleState;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Glue crawler runs")
class GlueCrawlerRunTest {

    private static final String CRAWLER_NAME = TestFixtures.uniqueName("run_crawler");
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    private static GlueClient glue;

    @BeforeAll
    static void setup() {
        glue = TestFixtures.glueClient();
        glue.createCrawler(r -> r
                .name(CRAWLER_NAME)
                .role(ROLE)
                .targets(t -> t.s3Targets(s -> s.path("s3://raw/events")))
                .schedule("cron(0 2 * * ? *)")
                .tags(Map.of("team", "data")));
    }

    @AfterAll
    static void cleanup() {
        if (glue == null) {
            return;
        }
        try {
            glue.deleteCrawler(r -> r.name(CRAWLER_NAME));
        }
        catch (Exception ignored) {
            // Cleanup is best effort: the crawler name is unique to this run and the emulator is disposable.
        }
        glue.close();
    }

    @Test
    @DisplayName("StartCrawler completes a crawl that GetCrawler and GetCrawlerMetrics parse")
    void startAndReadACrawl() {
        glue.startCrawler(r -> r.name(CRAWLER_NAME));

        Crawler crawler = glue.getCrawler(r -> r.name(CRAWLER_NAME)).crawler();
        assertThat(crawler.state()).isEqualTo(CrawlerState.READY);
        assertThat(crawler.lastCrawl().status()).isEqualTo(LastCrawlStatus.SUCCEEDED);
        assertThat(crawler.lastCrawl().startTime()).isNotNull();

        CrawlerMetrics metrics = glue.getCrawlerMetrics(r -> r.crawlerNameList(CRAWLER_NAME))
                .crawlerMetricsList().get(0);
        assertThat(metrics.crawlerName()).isEqualTo(CRAWLER_NAME);
        assertThat(metrics.timeLeftSeconds()).isZero();
        assertThat(metrics.stillEstimating()).isFalse();

        assertThatThrownBy(() -> glue.stopCrawler(r -> r.name(CRAWLER_NAME)))
                .isInstanceOf(CrawlerNotRunningException.class);
    }

    @Test
    @DisplayName("ListCrawlers filters on tags and BatchGetCrawlers reports missing names")
    void listAndBatchGetCrawlers() {
        assertThat(glue.listCrawlers(r -> r.tags(Map.of("team", "data"))).crawlerNames()).contains(CRAWLER_NAME);

        BatchGetCrawlersResponse response = glue.batchGetCrawlers(r -> r.crawlerNames(CRAWLER_NAME, "absent_crawler"));
        assertThat(response.crawlers()).extracting(Crawler::name).containsExactly(CRAWLER_NAME);
        assertThat(response.crawlersNotFound()).containsExactly("absent_crawler");
    }

    @Test
    @DisplayName("The crawler schedule can be stopped, started and replaced")
    void schedule() {
        glue.stopCrawlerSchedule(r -> r.crawlerName(CRAWLER_NAME));
        assertThat(glue.getCrawler(r -> r.name(CRAWLER_NAME)).crawler().schedule().state())
                .isEqualTo(ScheduleState.NOT_SCHEDULED);

        glue.startCrawlerSchedule(r -> r.crawlerName(CRAWLER_NAME));
        glue.updateCrawlerSchedule(r -> r.crawlerName(CRAWLER_NAME).schedule("cron(30 3 * * ? *)"));
        Crawler crawler = glue.getCrawler(r -> r.name(CRAWLER_NAME)).crawler();
        assertThat(crawler.schedule().state()).isEqualTo(ScheduleState.SCHEDULED);
        assertThat(crawler.schedule().scheduleExpression()).isEqualTo("cron(30 3 * * ? *)");
    }
}
