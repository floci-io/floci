package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cur.model.ReportDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure unit test for {@link CurService#listAllReportsByAccount} — no Quarkus
 * boot. Simulates two distinct accounts writing reports through an
 * {@link AccountAwareStorageBackend} backed by {@link InMemoryStorage}, then
 * asserts the daily-scheduler iteration sees BOTH partitions, not just the
 * default account.
 */
class CurAccountIsolationTest {

    @Test
    void listAllReportsByAccount_returnsEntriesFromEveryAccount() {
        InMemoryStorage<String, ReportDefinition> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<ReportDefinition> aware =
                new AccountAwareStorageBackend<>(raw, null, "000000000000");

        // Two accounts, two reports each. Account 111 owns "alpha"+"bravo",
        // account 222 owns "charlie".
        aware.putForAccount("111111111111", "us-east-1::alpha", reportNamed("alpha", "111111111111"));
        aware.putForAccount("111111111111", "us-east-1::bravo", reportNamed("bravo", "111111111111"));
        aware.putForAccount("222222222222", "us-east-1::charlie", reportNamed("charlie", "222222222222"));

        CurService service = new CurService(aware,
                new RegionResolver("us-east-1", "000000000000"));

        Map<String, List<ReportDefinition>> grouped = service.listAllReportsByAccount();
        assertNotNull(grouped);
        assertThat(grouped, hasKey("111111111111"));
        assertThat(grouped, hasKey("222222222222"));
        assertThat(grouped.get("111111111111").stream().map(ReportDefinition::getReportName).toList(),
                containsInAnyOrder("alpha", "bravo"));
        assertThat(grouped.get("222222222222").stream().map(ReportDefinition::getReportName).toList(),
                contains("charlie"));
    }

    @Test
    void listAllReportsByAccount_attributesFallbackAccountWhenOwnerMissing() {
        InMemoryStorage<String, ReportDefinition> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<ReportDefinition> aware =
                new AccountAwareStorageBackend<>(raw, null, "999988887777");

        // Pre-existing entry without ownerAccountId set (e.g. data persisted by
        // an older version of Floci) must still surface, attributed to the
        // configured default account so the daily loop won't skip it.
        ReportDefinition orphan = reportNamed("orphan", null);
        aware.putForAccount("999988887777", "us-east-1::orphan", orphan);

        CurService service = new CurService(aware,
                new RegionResolver("us-east-1", "999988887777"));

        Map<String, List<ReportDefinition>> grouped = service.listAllReportsByAccount();
        assertThat(grouped, hasKey("999988887777"));
        assertThat(grouped.get("999988887777").get(0).getReportName(), equalTo("orphan"));
    }

    private static ReportDefinition reportNamed(String name, String ownerAccountId) {
        ReportDefinition r = new ReportDefinition();
        r.setReportName(name);
        r.setTimeUnit("DAILY");
        r.setFormat("Parquet");
        r.setCompression("Parquet");
        r.setS3Bucket("valid-bucket");
        r.setS3Region("us-east-1");
        r.setOwnerAccountId(ownerAccountId);
        return r;
    }
}
