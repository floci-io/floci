package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceContext;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFormationResourceContextTest {

    private static final String ACCOUNT = "000000000001";
    private final AccountAwareStorageBackend<Stack> stacks = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final CloudFormationService service = service();

    private CloudFormationService service() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("000000000099");
        StorageFactory storage = mock(StorageFactory.class);
        when(storage.<Stack>create(eq("cloudformation"), eq("cloudformation-stacks.json"), any())).thenReturn(stacks);
        when(storage.<String>create(eq("cloudformation"), eq("cloudformation-exports.json"), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        return new CloudFormationService(mock(CfnResourceDispatcher.class), mock(S3Service.class),
                mock(SsmService.class), mock(CfnDynamicReferences.class), new ObjectMapper(), config,
                resolver, Clock.systemUTC(), storage);
    }

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    void lookupCapturesAccountAndStableOwnerAcrossRegionsIncludingNestedStacks() {
        Stack owner = stack("owner", ACCOUNT, "us-east-1", "UPDATE_FAILED", true);
        Stack sameOwnerClone = stack("owner", ACCOUNT, "us-east-1", "UPDATE_COMPLETE", true);
        Stack nested = stack("nested-child", ACCOUNT, "eu-west-1", "CREATE_COMPLETE", true);
        Stack foreign = stack("foreign", "000000000002", "us-east-1", "CREATE_COMPLETE", true);
        add(sameOwnerClone);
        add(nested);
        add(foreign);
        Stack deleted = stack("deleted", ACCOUNT, "us-east-1", "CREATE_COMPLETE", true);
        deleted.setStatus("DELETE_COMPLETE");
        add(deleted);
        service.loadPersistedState();
        CfnResourceContext context = service.resourceContext(owner);
        owner.setAccountId("000000000099");
        owner.setStackId("changed-owner");
        List<StackResource> snapshots = context.otherManagedResources().get();
        assertEquals(1, snapshots.size());
        assertEquals("nested-child-physical", snapshots.getFirst().getPhysicalId());
        nested.getResources().get("Resource").setPhysicalId("changed");
        nested.getResources().get("Resource").getAttributes().put("marker", "changed");
        assertEquals("nested-child-physical", snapshots.getFirst().getPhysicalId());
        assertEquals("original", snapshots.getFirst().getAttributes().get("marker"));
    }

    @ParameterizedTest
    @CsvSource({"CREATE_COMPLETE,false,true", "UPDATE_COMPLETE,false,true", "UPDATE_FAILED,false,true",
            "DELETE_FAILED,false,true", "UPDATE_IN_PROGRESS,false,true", "DELETE_IN_PROGRESS,false,true",
            "CREATE_FAILED,false,false", "CREATE_FAILED,true,true", "CREATE_IN_PROGRESS,false,false",
            "CREATE_IN_PROGRESS,true,true", "DELETE_COMPLETE,true,false", "DELETE_SKIPPED,true,false"})
    void claimsOnlyResourcesWithCurrentManagement(String status, boolean owned, boolean expected) {
        Stack owner = stack("owner", ACCOUNT, "us-east-1", "UPDATE_COMPLETE", true);
        Stack other = stack("other", ACCOUNT, "us-east-1", status, owned);
        add(other);
        service.loadPersistedState();
        CfnResourceContext context = service.resourceContext(owner);
        assertEquals(expected, context.managedElsewhere(resource -> true));
        other.getResources().get("Resource").setPhysicalId(null);
        assertFalse(context.managedElsewhere(resource -> true));
    }

    @Test
    void legacyStacksUseDefaultAccountAndHistoricalAttributesAreNotClaims() {
        Stack owner = stack("owner", ACCOUNT, "us-east-1", "UPDATE_COMPLETE", true);
        Stack legacy = stack("legacy", null, "us-east-1", "DELETE_SKIPPED", true);
        legacy.getResources().get("Resource").getAttributes().put("cleanup", "historical-address");
        stacks.putForAccount(ACCOUNT, "us-east-1:legacy", legacy);
        service.loadPersistedState();
        CfnResourceContext context = service.resourceContext(owner);
        assertFalse(context.managedElsewhere(resource -> true));
        legacy.getResources().get("Resource").setStatus("DELETE_FAILED");
        assertTrue(context.managedElsewhere(resource -> true));
        assertEquals(ACCOUNT, legacy.getAccountId());
    }

    private void add(Stack stack) {
        stacks.putForAccount(stack.getAccountId(), stack.getRegion() + ":" + stack.getStackName(), stack);
    }

    private static Stack stack(String id, String account, String region, String status, boolean owned) {
        Stack stack = new Stack();
        stack.setStackId(id);
        stack.setStackName(id);
        stack.setAccountId(account);
        stack.setRegion(region);
        stack.setStatus("CREATE_COMPLETE");
        StackResource resource = new StackResource();
        resource.setLogicalId("Resource");
        resource.setPhysicalId(id + "-physical");
        resource.setResourceType("AWS::Test::Resource");
        resource.setStatus(status);
        resource.getAttributes().put("marker", "original");
        if (owned) {
            resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        }
        stack.getResources().put("Resource", resource);
        return stack;
    }
}
