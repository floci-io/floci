package io.floci.aws.services.athena;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.athena.model.NamedQuery;
import io.floci.aws.services.athena.model.QueryExecution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What Athena gives the request-resources table through a query execution or a named query: the workgroup it runs in,
 * which Athena checks a request naming it on.
 */
class AthenaStoredResourceArnsTest {

    private static final String REGION = "eu-west-1";
    private static final String WORKGROUPS = "arn:aws:athena:" + REGION + ":000000000000:workgroup/";

    private final AthenaService athena = mock(AthenaService.class);
    private final AthenaNamedQueryService namedQueries = mock(AthenaNamedQueryService.class);
    private final AthenaStoredResourceArns stored = new AthenaStoredResourceArns(
            new RegionResolver("us-east-1", "000000000000"), athena, namedQueries);

    @Test
    void aQueryExecutionOrANamedQueryReachesTheWorkgroupItRunsIn() {
        QueryExecution execution = new QueryExecution();
        execution.setWorkGroup("etl");
        when(athena.findQueryExecution("q-1")).thenReturn(Optional.of(execution));
        when(athena.findQueryExecutionRegion("q-1")).thenReturn(Optional.of(REGION));
        when(namedQueries.findNamedQuery(REGION, "n-1"))
                .thenReturn(Optional.of(new NamedQuery("n-1", "daily", "db", "select 1", null, "reports")));

        assertEquals(List.of(WORKGROUPS + "etl"), stored.through("QueryExecutionId", "q-1", REGION));
        assertEquals(List.of(WORKGROUPS + "reports"), stored.through("NamedQueryId", "n-1", REGION));
    }

    @Test
    void aQueryExecutionIsCheckedOnItsWorkgroupInTheRegionItWasStartedIn() {
        // A request sent to another Region is checked on the execution's own workgroup, so neither a grant on that
        // Region's workgroup of the same name passes nor a deny on the execution's own is passed by.
        QueryExecution execution = new QueryExecution();
        execution.setWorkGroup("etl");
        when(athena.findQueryExecution("q-3")).thenReturn(Optional.of(execution));
        when(athena.findQueryExecutionRegion("q-3")).thenReturn(Optional.of("us-east-1"));

        assertEquals(List.of("arn:aws:athena:us-east-1:000000000000:workgroup/etl"),
                stored.through("QueryExecutionId", "q-3", REGION));
    }

    @Test
    void aQueryExecutionNamingNoWorkgroupRunsInPrimary() {
        when(athena.findQueryExecution("q-2")).thenReturn(Optional.of(new QueryExecution()));
        when(athena.findQueryExecutionRegion("q-2")).thenReturn(Optional.of(REGION));
        assertEquals(List.of(WORKGROUPS + "primary"), stored.through("QueryExecutionId", "q-2", REGION));
    }

    @Test
    void anIdAthenaHoldsNoneOfReachesNothing() {
        when(athena.findQueryExecution("missing")).thenReturn(Optional.empty());
        when(namedQueries.findNamedQuery(REGION, "missing")).thenReturn(Optional.empty());
        assertEquals(List.of(), stored.through("QueryExecutionId", "missing", REGION));
        assertEquals(List.of(), stored.through("NamedQueryId", "missing", REGION));
        assertEquals(List.of(), stored.through("NotebookId", "nb-1", REGION));
    }

    @Test
    void aQueryExecutionWhoseRegionWasNotRecordedReachesNothing() {
        when(athena.findQueryExecution("q-old")).thenReturn(Optional.of(new QueryExecution()));
        when(athena.findQueryExecutionRegion("q-old")).thenReturn(Optional.empty());
        assertEquals(List.of(), stored.through("QueryExecutionId", "q-old", REGION));
    }
}
