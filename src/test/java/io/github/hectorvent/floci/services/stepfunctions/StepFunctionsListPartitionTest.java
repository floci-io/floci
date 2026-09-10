package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Step Functions is the only service that keys its stores by the full ARN; the others key by
 * {@code region::name}. Once ARNs carried the region's real partition, the list operations still
 * scanned for a {@code arn:aws:states:} prefix, so a state machine or activity created in GovCloud
 * or China was stored under {@code arn:aws-us-gov:...} and listing returned nothing: the write path
 * and the read path disagreed about the same resource.
 *
 * <p>A real {@link RegionResolver} is used deliberately, not a mock, because the whole point is
 * that the list prefix agrees with what {@code buildArn} actually produced.
 */
class StepFunctionsListPartitionTest {

    private static final String ACCOUNT = "000000000000";

    private StepFunctionsService buildService() {
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(AccountAwareStorageBackend.<StateMachine>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<Execution>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<Activity>inMemory(ACCOUNT))
                .when(factory).create(anyString(), anyString(), any());

        // aslExecutor stays null: neither CreateStateMachine nor CreateActivity reaches it, and
        // AslExecutor cannot be class-loaded in this sandbox because it needs Vert.x.
        return new StepFunctionsService(factory, new RegionResolver("us-east-1", ACCOUNT), null,
                new ObjectMapper(), mock(SfnMockLoader.class));
    }

    private static final String DEFINITION =
            "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"End\":true}}}";

    @ParameterizedTest
    @CsvSource({
            "us-east-1,      arn:aws:states:",
            "us-gov-west-1,  arn:aws-us-gov:states:",
            "cn-north-1,     arn:aws-cn:states:",
            "us-isob-east-1, arn:aws-iso-b:states:"})
    void listsAStateMachineCreatedInThatPartition(String region, String expectedArnPrefix) {
        StepFunctionsService service = buildService();

        StateMachine created = service.createStateMachine("sm-" + region, DEFINITION,
                "arn:aws:iam::" + ACCOUNT + ":role/r", "STANDARD", region, null);

        assertTrue(created.getStateMachineArn().startsWith(expectedArnPrefix),
                "created ARN was " + created.getStateMachineArn());

        List<StateMachine> listed = service.listStateMachines(region);

        assertEquals(1, listed.size(),
                "ListStateMachines found nothing in " + region + " for " + created.getStateMachineArn());
        assertEquals(created.getStateMachineArn(), listed.getFirst().getStateMachineArn());
    }

    @ParameterizedTest
    @CsvSource({
            "us-east-1,      arn:aws:states:",
            "us-gov-west-1,  arn:aws-us-gov:states:",
            "cn-north-1,     arn:aws-cn:states:",
            "us-isob-east-1, arn:aws-iso-b:states:"})
    void listsAnActivityCreatedInThatPartition(String region, String expectedArnPrefix) {
        StepFunctionsService service = buildService();

        Activity created = service.createActivity("act-" + region, region, null);

        assertTrue(created.getActivityArn().startsWith(expectedArnPrefix),
                "created ARN was " + created.getActivityArn());

        List<Activity> listed = service.listActivities(region);

        assertEquals(1, listed.size(),
                "ListActivities found nothing in " + region + " for " + created.getActivityArn());
        assertEquals(created.getActivityArn(), listed.getFirst().getActivityArn());
    }

    /** A list must still be scoped to its own region, and now also to its own partition. */
    @Test
    void doesNotListAcrossRegions() {
        StepFunctionsService service = buildService();
        service.createActivity("gov", "us-gov-west-1", null);
        service.createActivity("commercial", "us-east-1", null);

        assertEquals(1, service.listActivities("us-gov-west-1").size());
        assertEquals(1, service.listActivities("us-east-1").size());
        assertEquals("gov", service.listActivities("us-gov-west-1").getFirst().getName());
    }
}
