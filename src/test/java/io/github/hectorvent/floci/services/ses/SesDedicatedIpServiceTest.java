package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted dedicated-IP-pool domain: create/get/list/delete plus
 * the scaling-mode and duplicate validation. The service is constructed with just its own store — no
 * 14-argument SesService needed.
 */
class SesDedicatedIpServiceTest {

    private static final String REGION = "us-east-1";
    private SesDedicatedIpService service;

    @BeforeEach
    void setUp() {
        service = new SesDedicatedIpService(new InMemoryStorage<>());
    }

    @Test
    void create_defaultsScalingModeToStandard() {
        DedicatedIpPool pool = service.createDedicatedIpPool("pool-a", null, null, REGION);
        assertEquals("pool-a", pool.getPoolName());
        assertEquals("STANDARD", pool.getScalingMode());
        assertTrue(service.dedicatedIpPoolExists("pool-a", REGION));
    }

    @Test
    void create_rejectsBlankName() {
        assertThrows(AwsException.class, () -> service.createDedicatedIpPool(" ", "STANDARD", null, REGION));
    }

    @Test
    void create_rejectsInvalidScalingMode() {
        assertThrows(AwsException.class, () -> service.createDedicatedIpPool("pool-a", "TURBO", null, REGION));
    }

    @Test
    void create_rejectsDuplicate() {
        service.createDedicatedIpPool("pool-a", "STANDARD", null, REGION);
        assertThrows(AwsException.class, () -> service.createDedicatedIpPool("pool-a", "STANDARD", null, REGION));
    }

    @Test
    void create_storesTags() {
        DedicatedIpPool pool = service.createDedicatedIpPool("pool-a", "STANDARD",
                List.of(new Tag("env", "dev")), REGION);
        assertEquals(List.of(new Tag("env", "dev")), pool.getTags());
        assertEquals(List.of(new Tag("env", "dev")),
                service.getDedicatedIpPool("pool-a", REGION).getTags());
    }

    @Test
    void create_invalidTags_isAtomic_poolNotPersisted() {
        assertThrows(AwsException.class, () -> service.createDedicatedIpPool("pool-a", "STANDARD",
                List.of(new Tag("aws:reserved", "x")), REGION));
        assertFalse(service.dedicatedIpPoolExists("pool-a", REGION));
    }

    @Test
    void tag_mergesAndUntagRemoves() {
        service.createDedicatedIpPool("pool-a", "STANDARD", List.of(new Tag("env", "dev")), REGION);
        service.tag("pool-a", REGION, List.of(new Tag("env", "prod"), new Tag("owner", "alice")));
        assertEquals(List.of(new Tag("env", "prod"), new Tag("owner", "alice")),
                service.listTags("pool-a", REGION));

        service.untag("pool-a", REGION, List.of("env"));
        assertEquals(List.of(new Tag("owner", "alice")), service.listTags("pool-a", REGION));
    }

    @Test
    void untag_immediatelyAfterCreateWithImmutableTags_works() {
        service.createDedicatedIpPool("pool-a", "STANDARD", List.of(new Tag("env", "dev")), REGION);
        service.untag("pool-a", REGION, List.of("env"));
        assertEquals(List.of(), service.listTags("pool-a", REGION));
    }

    @Test
    void tag_missingPool_throws() {
        assertThrows(AwsException.class,
                () -> service.tag("ghost", REGION, List.of(new Tag("env", "dev"))));
    }

    @Test
    void get_missingThrows() {
        assertThrows(AwsException.class, () -> service.getDedicatedIpPool("ghost", REGION));
    }

    @Test
    void list_isSortedAndPerRegion() {
        service.createDedicatedIpPool("pool-b", "STANDARD", null, REGION);
        service.createDedicatedIpPool("pool-a", "STANDARD", null, REGION);
        service.createDedicatedIpPool("pool-other", "STANDARD", null, "eu-west-1");

        assertEquals(List.of("pool-a", "pool-b"), service.listDedicatedIpPools(REGION));
    }

    @Test
    void delete_removesPool() {
        service.createDedicatedIpPool("pool-a", "MANAGED", null, REGION);
        service.deleteDedicatedIpPool("pool-a", REGION);
        assertFalse(service.dedicatedIpPoolExists("pool-a", REGION));
    }

    @Test
    void delete_missingThrows() {
        assertThrows(AwsException.class, () -> service.deleteDedicatedIpPool("ghost", REGION));
    }
}
