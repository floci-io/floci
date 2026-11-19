package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.cloudformation.model.StackInstance;
import io.floci.aws.services.cloudformation.model.StackSet;
import io.floci.aws.services.cloudformation.model.StackSetAutoDeploymentTarget;
import io.floci.aws.services.cloudformation.model.StackSetOperation;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.StackSetErrors.assertNotFound;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * A stack set stored before stack sets were kept per Region, with its instance, operation and automatic deployment
 * target, recorded no Region: it is the default Region's, listed and reached there and not in another Region, where
 * its name is free. The stores are account stores, as in production.
 */
class StackSetLegacyRecordsTest {

    private static final String ACCOUNT = "123456789012";
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String OTHER_REGION = "eu-west-1";
    private static final String SET = "stored-before";
    private static final String ID = SET + ":5f0c3a52-8d4e-4c41-9b7a-2f1e6d0a9c11";
    private static final String TARGET_ACCOUNT = "210987654321";
    private static final String OU = "ou-abcd-abcdefgh";

    private final RequestRegion regions = new RequestRegion();
    private StackSetService service;

    @BeforeEach
    void storeRecordsWithoutARegion() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
        StackSet stackSet = new StackSet();
        stackSet.setStackSetName(SET);
        stackSet.setStackSetId(ID);
        stackSet.setTemplateBody("{\"Resources\":{}}");
        stackSet.setPermissionModel("SERVICE_MANAGED");
        stackSet.setTags(Map.of("team", "red"));
        storage.create("cloudformation", "cloudformation-stacksets.json",
                new TypeReference<Map<String, StackSet>>() {}).put(SET, stackSet);
        StackInstance instance = new StackInstance();
        instance.setStackSetId(ID);
        instance.setStackSetName(SET);
        instance.setAccount(TARGET_ACCOUNT);
        instance.setRegion("eu-central-1");
        storage.create("cloudformation", "cloudformation-stackset-instances.json",
                new TypeReference<Map<String, StackInstance>>() {})
                .put(SET + ":" + TARGET_ACCOUNT + ":eu-central-1", instance);
        storage.create("cloudformation", "cloudformation-stackset-operations.json",
                new TypeReference<Map<String, StackSetOperation>>() {})
                .put(SET + ":op-1", new StackSetOperation("op-1", SET, "CREATE"));
        storage.create("cloudformation", "cloudformation-stackset-auto-targets.json",
                new TypeReference<Map<String, StackSetAutoDeploymentTarget>>() {})
                .put(SET + "::" + OU, new StackSetAutoDeploymentTarget(SET, OU, List.of("eu-central-1")));
        service = new StackSetService(mock(CloudFormationService.class), storage, mock(OrganizationsService.class),
                regions);
    }

    @Test
    void aStackSetStoredBeforeIsListedAndReachedInTheDefaultRegionOnly() {
        assertEquals(ID, service.describeStackSet(SET).getStackSetId());
        assertEquals(List.of(SET), service.listStackSets().stream().map(StackSet::getStackSetName).toList());
        assertEquals(List.of(TARGET_ACCOUNT + "/eu-central-1"), service.listStackInstances(SET, null, null).stream()
                .map(instance -> instance.getAccount() + "/" + instance.getRegion()).toList());
        assertEquals("CREATE", service.describeStackSetOperation(SET, "op-1").getAction());
        assertEquals(List.of(OU), service.organizationalUnitIds(SET));
        assertEquals(Map.of("team", "red"), service.storedTags(arn(DEFAULT_REGION)));

        regions.region = OTHER_REGION;

        assertNotFound(() -> service.describeStackSet(SET), SET);
        assertEquals(List.of(), service.listStackSets());
        assertNotFound(() -> service.listStackInstances(SET, null, null), SET);
        assertNotFound(() -> service.describeStackSetOperation(SET, "op-1"), SET);
        assertEquals(Map.of(), service.storedTags(arn(OTHER_REGION)));

        StackSet created = service.createStackSet(SET, "{\"Resources\":{}}", null, null, null, null);
        assertNotEquals(ID, created.getStackSetId());
        regions.region = DEFAULT_REGION;
        assertEquals(ID, service.describeStackSet(SET).getStackSetId());
    }

    private static String arn(String region) {
        return "arn:aws:cloudformation:" + region + ":" + ACCOUNT + ":stackset/" + ID;
    }

    /** A resolver whose request is in the Region a test sets, {@code us-east-1} being the default Region. */
    private static final class RequestRegion extends RegionResolver {
        private volatile String region = DEFAULT_REGION;

        RequestRegion() {
            super(DEFAULT_REGION, ACCOUNT);
        }

        @Override
        public String getRegion() {
            return region;
        }
    }
}
