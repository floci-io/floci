package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BatchGetCrawlersResponse;
import software.amazon.awssdk.services.glue.model.ConnectionPropertyKey;
import software.amazon.awssdk.services.glue.model.ConnectionType;
import software.amazon.awssdk.services.glue.model.Crawler;
import software.amazon.awssdk.services.glue.model.CrawlerMetrics;
import software.amazon.awssdk.services.glue.model.CrawlerNotRunningException;
import software.amazon.awssdk.services.glue.model.CrawlerState;
import software.amazon.awssdk.services.glue.model.LastCrawlStatus;
import software.amazon.awssdk.services.glue.model.ScheduleState;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.ClusterNotFoundException;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;

import java.time.Duration;
import java.time.Instant;
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

    @Test
    @DisplayName("A JDBC crawl naming a missing connection ends FAILED with an error message")
    void jdbcCrawlWithAMissingConnectionFails() throws InterruptedException {
        String name = TestFixtures.uniqueName("jdbc_missing_conn");
        glue.createCrawler(r -> r
                .name(name)
                .role(ROLE)
                .databaseName("default")
                .targets(t -> t.jdbcTargets(j -> j.connectionName("absent-connection").path("dev/public/%"))));
        try {
            glue.startCrawler(r -> r.name(name));

            Crawler crawler = awaitReady(name);
            assertThat(crawler.lastCrawl().status()).isEqualTo(LastCrawlStatus.FAILED);
            assertThat(crawler.lastCrawl().errorMessage()).contains("absent-connection");
            assertThat(glue.getCrawlerMetrics(r -> r.crawlerNameList(name)).crawlerMetricsList().get(0)
                    .tablesCreated()).isZero();
        } finally {
            glue.deleteCrawler(r -> r.name(name));
        }
    }

    @Test
    @DisplayName("A JDBC crawl of a Redshift cluster writes its tables to the Data Catalog")
    void jdbcCrawlOfARedshiftCluster() throws InterruptedException {
        String clusterId = TestFixtures.uniqueName("glue-crawl-rs");
        String database = TestFixtures.uniqueName("rs_lake").replace('-', '_');
        String connection = TestFixtures.uniqueName("rs-conn");
        String crawlerName = TestFixtures.uniqueName("rs_crawler");
        RedshiftClient redshift = TestFixtures.redshiftClient();
        RedshiftDataClient data = TestFixtures.redshiftDataClient();
        try {
            redshift.createCluster(r -> r
                    .clusterIdentifier(clusterId)
                    .nodeType("dc2.large")
                    .masterUsername("admin")
                    .masterUserPassword("password123"));
            execute(data, clusterId, "CREATE TABLE crawl_orders (id int, amount numeric(10,2), note varchar(20))");
            glue.createDatabase(r -> r.databaseInput(d -> d.name(database)));
            glue.createConnection(r -> r.connectionInput(c -> c
                    .name(connection)
                    .connectionType(ConnectionType.JDBC)
                    .connectionProperties(Map.of(
                            ConnectionPropertyKey.JDBC_CONNECTION_URL,
                            "jdbc:redshift://" + clusterId + ".abc123.us-east-1.redshift.amazonaws.com:5439/dev",
                            ConnectionPropertyKey.USERNAME, "admin",
                            ConnectionPropertyKey.PASSWORD, "password123"))));
            glue.createCrawler(r -> r
                    .name(crawlerName)
                    .role(ROLE)
                    .databaseName(database)
                    .targets(t -> t.jdbcTargets(j -> j.connectionName(connection).path("dev/public/crawl%"))));

            glue.startCrawler(r -> r.name(crawlerName));

            Crawler crawler = awaitReady(crawlerName);
            assertThat(crawler.lastCrawl().status()).isEqualTo(LastCrawlStatus.SUCCEEDED);
            Table table = glue.getTable(r -> r.databaseName(database).name("dev_public_crawl_orders")).table();
            assertThat(table.storageDescriptor().columns())
                    .extracting(c -> c.name() + ":" + c.type())
                    .containsExactly("id:int", "amount:decimal(10,2)", "note:string");
            assertThat(table.storageDescriptor().location()).isEqualTo("dev.public.crawl_orders");
            assertThat(table.parameters()).containsEntry("classification", "redshift")
                    .containsEntry("connectionName", connection);
            assertThat(glue.getCrawlerMetrics(r -> r.crawlerNameList(crawlerName)).crawlerMetricsList().get(0)
                    .tablesCreated()).isEqualTo(1);
        } finally {
            cleanUp(() -> glue.deleteCrawler(r -> r.name(crawlerName)));
            cleanUp(() -> glue.deleteConnection(r -> r.connectionName(connection)));
            cleanUp(() -> glue.deleteDatabase(r -> r.name(database)));
            try {
                redshift.deleteCluster(r -> r.clusterIdentifier(clusterId).skipFinalClusterSnapshot(true));
            } catch (ClusterNotFoundException ignored) {
                // The cluster does not exist when creation failed.
            }
            data.close();
            redshift.close();
        }
    }

    private static Crawler awaitReady(String name) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            Crawler crawler = glue.getCrawler(r -> r.name(name)).crawler();
            if (crawler.state() == CrawlerState.READY && crawler.lastCrawl() != null) {
                return crawler;
            }
            Thread.sleep(TestFixtures.isRealAws() ? 5_000L : 250L);
        }
        throw new IllegalStateException("Crawler " + name + " did not finish in time");
    }

    private static void execute(RedshiftDataClient data, String clusterId, String sql) {
        String id = data.executeStatement(r -> r.clusterIdentifier(clusterId).dbUser("admin").database("dev")
                .sql(sql).waitTimeSeconds(30)).id();
        DescribeStatementResponse describe = data.describeStatement(r -> r.id(id));
        assertThat(describe.status())
                .as("statement %s error: %s", id, describe.error())
                .isEqualTo(StatusString.FINISHED);
    }

    private static void cleanUp(Runnable step) {
        try {
            step.run();
        } catch (Exception ignored) {
            // Cleanup is best effort: every name is unique to this run and the emulator is disposable.
        }
    }
}
