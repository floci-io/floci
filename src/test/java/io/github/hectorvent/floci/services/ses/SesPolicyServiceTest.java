package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted identity-policy domain. The identity-existence check
 * stays in the SesService facade, so this service is a pure policy store — unit-tested with just its
 * store and an ObjectMapper, and it exercises the reverse-coupling helper deletePoliciesForIdentity.
 */
class SesPolicyServiceTest {

    private static final String REGION = "us-east-1";
    private static final String IDENTITY = "sender@example.com";
    private SesPolicyService service;

    @BeforeEach
    void setUp() {
        service = new SesPolicyService(new InMemoryStorage<>(), new ObjectMapper());
    }

    @Test
    void create_thenList_roundTrips() {
        service.createEmailIdentityPolicy(IDENTITY, "p1", "{\"Version\":\"2012-10-17\"}", REGION);
        assertTrue(service.listAllPolicies(IDENTITY, REGION).containsKey("p1"));
    }

    @Test
    void create_duplicate_throwsAlreadyExists() {
        service.createEmailIdentityPolicy(IDENTITY, "p1", "{}", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEmailIdentityPolicy(IDENTITY, "p1", "{}", REGION));
        assertEquals("AlreadyExistsException", e.getErrorCode());
    }

    @Test
    void update_missing_throwsNotFound() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.updateEmailIdentityPolicy(IDENTITY, "ghost", "{}", REGION));
        assertEquals("NotFoundException", e.getErrorCode());
    }

    @Test
    void invalidPolicyName_throws() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEmailIdentityPolicy(IDENTITY, "bad name!", "{}", REGION));
        assertEquals("InvalidParameterValue", e.getErrorCode());
    }

    @Test
    void enforcesPerIdentityLimit() {
        for (int i = 0; i < SesPolicyService.MAX_POLICIES_PER_IDENTITY; i++) {
            service.createEmailIdentityPolicy(IDENTITY, "p" + i, "{}", REGION);
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEmailIdentityPolicy(IDENTITY, "one-too-many", "{}", REGION));
        assertEquals("LimitExceededException", e.getErrorCode());
    }

    @Test
    void deletePoliciesForIdentity_purgesAll() {
        service.putIdentityPolicy(IDENTITY, "p1", "{}", REGION);
        service.putIdentityPolicy(IDENTITY, "p2", "{}", REGION);
        service.deletePoliciesForIdentity(IDENTITY, REGION);
        assertTrue(service.listAllPolicies(IDENTITY, REGION).isEmpty());
    }
}
