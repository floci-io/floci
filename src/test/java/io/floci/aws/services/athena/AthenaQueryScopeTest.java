package io.floci.aws.services.athena;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.QueryExecutionContext;
import io.floci.aws.services.athena.model.QueryExecutionState;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A query runs on a worker after the call that started it has returned, with no request scope of its own;
 * it still reads the Glue catalog of the account and Region it was started in, and its record stays in
 * that account. Runs under Quarkus, where a scope sets the account and the Region; the duck client is
 * stubbed and hands back the DDL the query was set up with.
 */
@QuarkusTest
class AthenaQueryScopeTest {

    private static final String REGION = "eu-west-1";

    @Inject
    StorageFactory storageFactory;

    @Inject
    GlueService glueService;

    @Inject
    GlueViewDdlBuilder ddlBuilder;

    @Inject
    RegionResolver regionResolver;

    @Inject
    Vertx vertx;

    @Test
    void aQueryReadsTheCatalogOfTheAccountAndRegionItWasStartedIn() throws Exception {
        String account = TestAccounts.randomId();
        String database = "scope_" + account;
        // The setup DDL the query reads the catalog through, and the account its S3 reads and writes go to.
        CompletableFuture<List<String>> handedToDuck = new CompletableFuture<>();
        FlociDuckClient duckClient = mock(FlociDuckClient.class);
        doAnswer(invocation -> {
            handedToDuck.complete(List.of(invocation.getArgument(1), invocation.getArgument(3)));
            return null;
        }).when(duckClient).execute(anyString(), any(), any(), any());
        AthenaService athena = new AthenaService(storageFactory, duckClient, glueService, mock(S3Service.class),
                realQueries(), vertx, ddlBuilder, regionResolver);
        try {
            String expected = RequestScopes.callAs(account, REGION, () -> {
                glueService.createDatabase(new Database(database));
                return ddlBuilder.build(database);
            });
            assertNotEquals(RequestScopes.callAs(account, "us-east-1", () -> ddlBuilder.build(database)), expected);
            QueryExecutionContext context = new QueryExecutionContext();
            context.setDatabase(database);

            String id = RequestScopes.callAs(account, REGION,
                    () -> athena.startQueryExecution("SELECT 1", "primary", context, null));

            assertEquals(List.of(expected, account), handedToDuck.get(10, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                    assertEquals(QueryExecutionState.SUCCEEDED, RequestScopes.callAs(account, REGION,
                            () -> athena.getQueryExecution(id).getStatus().getState())));
        } finally {
            RequestScopes.runAs(account, REGION, () -> glueService.deleteDatabase(database));
        }
    }

    /** A configuration under which a query is handed to the duck client rather than answered at once. */
    private static EmulatorConfig realQueries() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AthenaServiceConfig athena = mock(EmulatorConfig.AthenaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.athena()).thenReturn(athena);
        when(athena.mock()).thenReturn(false);
        return config;
    }
}
