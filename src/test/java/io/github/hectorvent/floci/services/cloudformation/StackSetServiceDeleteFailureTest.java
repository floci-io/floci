package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackInstance;
import io.github.hectorvent.floci.services.cloudformation.model.StackSetOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused coverage for how {@link StackSetService#deleteStackInstances} treats a failing underlying
 * {@code deleteStack}. A non-retained delete whose stack delete fails must report the operation as
 * FAILED and retain the instance record (AWS semantics) rather than swallowing the failure, dropping
 * the instance, and reporting a false SUCCEEDED.
 */
class StackSetServiceDeleteFailureTest {

    private static final String SET = "del-set";
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-east-1";

    private CloudFormationService cfnService;
    private StackSetService service;

    @BeforeEach
    void setUp() {
        cfnService = mock(CloudFormationService.class);
        // deployInstance drives the single-stack engine; stub it to a clean CREATE so the seeded
        // instance starts SUCCEEDED, isolating this test to the delete path.
        when(cfnService.executeChangeSet(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Stack created = new Stack();
        created.setStackName("StackSet-" + SET + "-" + ACCOUNT);
        created.setStackId("stack-id");
        created.setStatus("CREATE_COMPLETE");
        when(cfnService.describeStacks(anyString(), anyString())).thenReturn(List.of(created));

        service = new StackSetService(cfnService, new InMemoryStorageFactory());
        service.createStackSet(SET, "{\"Resources\":{}}", null, null, null, null);
        service.createStackInstances(SET, List.of(ACCOUNT), List.of(REGION));
    }

    @Test
    void nonRetainedDeleteFailureReportsFailedAndRetainsInstance() {
        when(cfnService.deleteStack(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        StackSetOperation op = service.deleteStackInstances(SET, List.of(ACCOUNT), List.of(REGION), false);

        // The operation must surface the failure, not a hardcoded SUCCEEDED.
        assertEquals("FAILED", op.getStatus());
        // The instance record is retained (AWS does not detach an instance whose stack delete failed)
        // and marked INOPERABLE/FAILED.
        List<StackInstance> remaining = service.listStackInstances(SET, null, null);
        assertEquals(1, remaining.size(), "failed-delete instance must be retained");
        assertEquals("INOPERABLE", remaining.get(0).getStatus());
        assertEquals("FAILED", remaining.get(0).getDetailedStatus());
    }

    @Test
    void nonRetainedDeleteSuccessReportsSucceededAndRemovesInstance() {
        when(cfnService.deleteStack(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        StackSetOperation op = service.deleteStackInstances(SET, List.of(ACCOUNT), List.of(REGION), false);

        assertEquals("SUCCEEDED", op.getStatus());
        assertTrue(service.listStackInstances(SET, null, null).isEmpty(),
                "successful delete must remove the instance");
    }

    @Test
    void retainStacksRemovesInstanceWithoutDeletingStack() {
        StackSetOperation op = service.deleteStackInstances(SET, List.of(ACCOUNT), List.of(REGION), true);

        assertEquals("SUCCEEDED", op.getStatus());
        assertTrue(service.listStackInstances(SET, null, null).isEmpty(),
                "RetainStacks detaches the instance from the StackSet");
        // RetainStacks=true must never invoke the underlying stack delete.
        org.mockito.Mockito.verify(cfnService, org.mockito.Mockito.never())
                .deleteStack(anyString(), anyString(), anyString());
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> StorageBackend<String, V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return new InMemoryStorage<>();
        }
    }
}
