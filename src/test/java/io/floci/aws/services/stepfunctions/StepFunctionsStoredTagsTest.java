package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.stepfunctions.model.Activity;
import io.floci.aws.services.stepfunctions.model.Execution;
import io.floci.aws.services.stepfunctions.model.MapRun;
import io.floci.aws.services.stepfunctions.model.RoutingConfiguration;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import io.floci.aws.services.stepfunctions.model.StateMachineAlias;
import io.floci.aws.services.stepfunctions.model.StateMachineVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class StepFunctionsStoredTagsTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String DEFINITION = "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/sfn";

    private AccountAwareStorageBackend<Execution> executionStore;
    private StepFunctionsService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        executionStore = AccountAwareStorageBackend.inMemory(ACCOUNT);
        doReturn(AccountAwareStorageBackend.<StateMachine>inMemory(ACCOUNT))
                .doReturn(executionStore)
                .doReturn(AccountAwareStorageBackend.<Activity>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<MapRun>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<StateMachineAlias>inMemory(ACCOUNT))
                .when(factory).create(anyString(), anyString(), any());
        service = new StepFunctionsService(factory, new RegionResolver(REGION, ACCOUNT), null, new ObjectMapper(),
                mock(SfnMockLoader.class), mock(StepFunctionsEventPublisher.class), Clock.systemUTC());
    }

    @Test
    void aStateMachinesVersionsAndExecutionsHaveItsTagsAndAnAliasNone() {
        String arn = service.createStateMachine("orders", DEFINITION, ROLE, "STANDARD", REGION,
                Map.of("team", "data")).getStateMachineArn();
        StateMachineVersion version = service.publishStateMachineVersion(arn, null, null);
        service.createStateMachineAlias("live", null,
                List.of(new RoutingConfiguration(version.getStateMachineVersionArn(), 100)));
        Execution execution = new Execution();
        execution.setExecutionArn("arn:aws:states:" + REGION + ":" + ACCOUNT + ":execution:orders:run-1");
        execution.setStateMachineArn(arn);
        executionStore.put(execution.getExecutionArn(), execution);

        assertEquals(Map.of("team", "data"), service.storedTags(arn));
        assertEquals(Map.of("team", "data"), service.storedTags(version.getStateMachineVersionArn()));
        assertEquals(Map.of("team", "data"), service.storedTags(execution.getExecutionArn()));
        assertEquals(Map.of(), service.storedTags(arn + ":live"));
        assertEquals(Map.of(), service.storedTags(arn + ":7"));
        assertEquals(Map.of(), service.storedTags(execution.getExecutionArn().replace("run-1", "run-2")));
        assertEquals(Map.of(), service.storedTags(arn.replace(ACCOUNT, "111111111111")));
        assertEquals(Map.of(), service.storedTags(arn.replace(REGION, "us-west-2")));

        service.tagResource(arn, Map.of("stage", "prod"));
        service.untagResource(arn, List.of("team"));

        assertEquals(Map.of("stage", "prod"), service.storedTags(version.getStateMachineVersionArn()));
    }

    @Test
    void anActivityHasItsOwnTagsAndTaggingPutsANewMapInPlaceOfTheHeldOne() {
        String arn = service.createActivity("worker", REGION, Map.of("team", "data")).getActivityArn();
        Map<String, String> held = service.listTags(arn);

        service.tagResource(arn, Map.of("stage", "prod"));
        service.untagResource(arn, List.of("team"));

        assertEquals(Map.of("stage", "prod"), service.storedTags(arn));
        assertEquals(Map.of("team", "data"), held);
        assertEquals(Map.of(), service.storedTags(arn.replace("worker", "other")));
    }
}
