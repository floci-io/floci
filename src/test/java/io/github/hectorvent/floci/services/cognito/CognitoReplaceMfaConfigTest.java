package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * replaceUserPoolMfaConfig, the CloudFormation EnabledMfas path: the pool ends up with exactly the
 * given factors, and the new settings are stored in one write without touching the pool a
 * concurrent sign-in may already be reading.
 */
class CognitoReplaceMfaConfigTest {

    private CognitoService service;
    private String poolId;

    @BeforeEach
    void setUp() {
        service = new CognitoService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", "cloudfront.net",
                new RegionResolver("us-east-1", "000000000000"), null, mock(AcmService.class),
                null, null, null, new MutableClock());
        poolId = service.createUserPool(Map.of("PoolName", "ReplaceMfaPool"), "us-east-1").getId();
        service.setUserPoolMfaConfig(poolId, "ON", true, null,
                Map.of("Subject", "Code", "Message", "Your code is {####}"), null);
    }

    @Test
    void factorsThatAreNotGivenAreTurnedOff() {
        service.replaceUserPoolMfaConfig(poolId, "OPTIONAL", null, Map.of("SmsAuthenticationMessage", "{####}"), null);

        UserPool pool = service.describeUserPool(poolId);
        assertEquals("OPTIONAL", pool.getMfaConfiguration());
        assertNull(pool.getSoftwareTokenMfaEnabled());
        assertNull(pool.getEmailMfaConfiguration());
        assertNotNull(pool.getSmsMfaConfiguration());
    }

    @Test
    void smsThatIsNotGivenStaysOnUntilMfaIsTurnedOff() {
        service.replaceUserPoolMfaConfig(poolId, "OPTIONAL", true, Map.of("SmsAuthenticationMessage", "{####}"), null);

        service.replaceUserPoolMfaConfig(poolId, "OPTIONAL", true, null, null);
        assertEquals("{####}", service.describeUserPool(poolId).getSmsMfaConfiguration().get("SmsAuthenticationMessage"));

        service.replaceUserPoolMfaConfig(poolId, "OFF", null, null, null);
        assertNull(service.describeUserPool(poolId).getSmsMfaConfiguration());
    }

    @Test
    void thePoolAReaderAlreadyHoldsKeepsItsSettings() {
        UserPool before = service.describeUserPool(poolId);

        service.replaceUserPoolMfaConfig(poolId, "OPTIONAL", null, Map.of(), null);

        assertEquals("ON", before.getMfaConfiguration());
        assertTrue(before.getSoftwareTokenMfaEnabled());
        assertNotNull(before.getEmailMfaConfiguration());
        assertEquals("OPTIONAL", service.describeUserPool(poolId).getMfaConfiguration());
    }

    @Test
    void tagsAddedWhileTheMfaSettingsAreReplacedAreKept() throws Exception {
        String arn = service.describeUserPool(poolId).getArn();
        int tags = 200;
        CyclicBarrier start = new CyclicBarrier(2);
        Thread tagger = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < tags; i++) {
                    service.tagResource(arn, Map.of("key" + i, "value"));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        tagger.start();
        start.await();
        while (tagger.isAlive()) {
            service.replaceUserPoolMfaConfig(poolId, "OPTIONAL", true, null, null);
        }
        tagger.join();

        assertEquals(tags, service.listTagsForResource(arn).size());
    }

    @Test
    void anInvalidConfigurationLeavesThePoolUnchanged() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.replaceUserPoolMfaConfig(poolId, "ON", null, null, null));

        assertEquals("InvalidParameterException", error.getErrorCode());
        UserPool pool = service.describeUserPool(poolId);
        assertEquals("ON", pool.getMfaConfiguration());
        assertTrue(pool.getSoftwareTokenMfaEnabled());
        assertNotNull(pool.getEmailMfaConfiguration());
    }
}
