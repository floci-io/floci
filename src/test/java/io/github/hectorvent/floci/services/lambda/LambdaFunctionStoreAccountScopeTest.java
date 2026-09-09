package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The S3 sync-reload observer runs with no {@code RequestContext}, so it sees the default
 * account's partition. It needs an all-accounts view to find the function an S3 update
 * belongs to, and an explicit-account save to put it back where it came from.
 */
class LambdaFunctionStoreAccountScopeTest {

    private static final String REGION = "us-east-1";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "222222222222";

    @Test
    void listAllAccountsSeesFunctionsOutsideTheAmbientPartition() {
        LambdaFunctionStore store = accountAwareStore();
        store.saveForAccount(OTHER_ACCOUNT, REGION, function("other-fn", OTHER_ACCOUNT));

        assertTrue(store.list(REGION).isEmpty(),
                "the ambient scan must not see another account's function");
        List<LambdaFunction> all = store.listAllAccounts(REGION);
        assertEquals(1, all.size());
        assertEquals("other-fn", all.get(0).getFunctionName());
    }

    @Test
    void listAllAccountsKeepsTheRegionAndLatestFilter() {
        LambdaFunctionStore store = accountAwareStore();
        store.saveForAccount(OTHER_ACCOUNT, REGION, function("latest-fn", OTHER_ACCOUNT));
        LambdaFunction published = function("latest-fn", OTHER_ACCOUNT);
        published.setVersion("1");
        store.saveForAccount(OTHER_ACCOUNT, REGION, published);
        store.saveForAccount(OTHER_ACCOUNT, "eu-west-1", function("elsewhere-fn", OTHER_ACCOUNT));

        List<LambdaFunction> all = store.listAllAccounts(REGION);

        assertEquals(1, all.size(), "published versions and other regions must stay excluded");
        assertEquals("$LATEST", all.get(0).getVersion());
    }

    @Test
    void saveForAccountWritesIntoTheOwningPartitionNotTheAmbientOne() {
        LambdaFunctionStore store = accountAwareStore();
        store.saveForAccount(OTHER_ACCOUNT, REGION, function("owned-fn", OTHER_ACCOUNT));

        assertTrue(store.getForAccount(OTHER_ACCOUNT, REGION, "owned-fn").isPresent());
        assertTrue(store.get(REGION, "owned-fn").isEmpty(),
                "the ambient (default-account) partition must stay clean");
    }

    @Test
    void listAllSeesEveryAccountSoRestartRehydrationCountsThemAll() {
        // The reserved-concurrency pool is per region and shared across accounts, so the
        // startup rehydration that feeds it has to see every account's functions or a restart
        // silently frees capacity that steady-state PutFunctionConcurrency had accounted for.
        LambdaFunctionStore store = accountAwareStore();
        store.saveForAccount(DEFAULT_ACCOUNT, REGION, function("default-fn", DEFAULT_ACCOUNT));
        store.saveForAccount(OTHER_ACCOUNT, REGION, function("other-fn", OTHER_ACCOUNT));

        assertEquals(2, store.listAll().size());
    }

    private LambdaFunctionStore accountAwareStore() {
        return new LambdaFunctionStore(AccountAwareStorageBackend.<LambdaFunction>inMemory(DEFAULT_ACCOUNT));
    }

    private LambdaFunction function(String name, String accountId) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(name);
        fn.setAccountId(accountId);
        fn.setVersion("$LATEST");
        fn.setFunctionArn("arn:aws:lambda:" + REGION + ":" + accountId + ":function:" + name);
        return fn;
    }
}
