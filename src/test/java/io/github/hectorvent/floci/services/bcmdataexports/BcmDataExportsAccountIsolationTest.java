package io.github.hectorvent.floci.services.bcmdataexports;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bcmdataexports.model.Export;
import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

/**
 * Pure unit test that simulates two distinct accounts owning BCM exports and
 * asserts {@link BcmDataExportsService#listAllExportsByAccount} surfaces both
 * — necessary for the daily emission scheduler to emit for accounts other
 * than the default.
 */
class BcmDataExportsAccountIsolationTest {

    @Test
    void listAllExportsByAccount_returnsEntriesFromEveryAccount() {
        InMemoryStorage<String, Export> rawExports = new InMemoryStorage<>();
        InMemoryStorage<String, ExportExecution> rawExecutions = new InMemoryStorage<>();
        AccountAwareStorageBackend<Export> exportStore =
                new AccountAwareStorageBackend<>(rawExports, null, "000000000000");
        AccountAwareStorageBackend<ExportExecution> executionStore =
                new AccountAwareStorageBackend<>(rawExecutions, null, "000000000000");

        exportStore.putForAccount("111111111111",
                "arn:aws:bcm-data-exports:us-east-1:111111111111:export/alpha",
                exportNamed("alpha", "111111111111"));
        exportStore.putForAccount("222222222222",
                "arn:aws:bcm-data-exports:us-east-1:222222222222:export/bravo",
                exportNamed("bravo", "222222222222"));

        BcmDataExportsService service = new BcmDataExportsService(exportStore, executionStore,
                new RegionResolver("us-east-1", "000000000000"));

        Map<String, List<Export>> grouped = service.listAllExportsByAccount();
        assertThat(grouped, hasKey("111111111111"));
        assertThat(grouped, hasKey("222222222222"));
        assertThat(grouped.get("111111111111").stream().map(Export::getName).toList(),
                containsInAnyOrder("alpha"));
        assertThat(grouped.get("222222222222").stream().map(Export::getName).toList(),
                containsInAnyOrder("bravo"));
    }

    @Test
    void recordExecution_landsInRequestedAccountPartition() {
        InMemoryStorage<String, Export> rawExports = new InMemoryStorage<>();
        InMemoryStorage<String, ExportExecution> rawExecutions = new InMemoryStorage<>();
        AccountAwareStorageBackend<Export> exportStore =
                new AccountAwareStorageBackend<>(rawExports, null, "000000000000");
        AccountAwareStorageBackend<ExportExecution> executionStore =
                new AccountAwareStorageBackend<>(rawExecutions, null, "000000000000");

        BcmDataExportsService service = new BcmDataExportsService(exportStore, executionStore,
                new RegionResolver("us-east-1", "000000000000"));

        ExportExecution exec = service.recordExecution("777777777777",
                "arn:aws:bcm-data-exports:us-east-1:777777777777:export/x", "SCHEDULE");

        // Looking up via the same account should find it.
        ExportExecution roundtrip = executionStore.getForAccount("777777777777",
                "arn:aws:bcm-data-exports:us-east-1:777777777777:export/x::"
                        + exec.getExecutionId()).orElseThrow();
        assertThat(roundtrip.getCreatedBy(), equalTo("SCHEDULE"));
    }

    private static Export exportNamed(String name, String ownerAccountId) {
        Export e = new Export();
        e.setName(name);
        e.setExportArn("arn:aws:bcm-data-exports:us-east-1:" + ownerAccountId + ":export/" + name);
        e.setOwnerAccountId(ownerAccountId);
        return e;
    }
}
