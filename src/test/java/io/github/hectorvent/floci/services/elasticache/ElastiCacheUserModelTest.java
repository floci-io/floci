package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Users persisted before the engine field existed must deserialize with the redis
 * default; the field initializer is what guarantees it, through the same
 * deserialization configuration the persistent storage backends use (ObjectMapper
 * plus JavaTimeModule).
 */
class ElastiCacheUserModelTest {

    @Test
    void legacyJsonWithoutEngineDeserializesAsRedis() throws Exception {
        ElastiCacheUser user = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(
                """
                {"userId":"legacy","userName":"legacy","authMode":"PASSWORD",\
                "passwords":["legacy-password-1"],"accessString":"on ~* +@all",\
                "status":"active","createdAt":"2026-08-15T10:00:00Z"}
                """,
                ElastiCacheUser.class);
        assertEquals("redis", user.getEngine());
    }

    @Test
    void isAccessStringOnEvaluatesTokensCorrectly() {
        assertTrue(ElastiCacheUser.isAccessStringOn("on ~* +@all"));
        assertTrue(ElastiCacheUser.isAccessStringOn("on"));
        assertTrue(ElastiCacheUser.isAccessStringOn("+@all on ~*"));
        assertTrue(ElastiCacheUser.isAccessStringOn("off +@all on"));

        assertFalse(ElastiCacheUser.isAccessStringOn("off -@all"));
        assertFalse(ElastiCacheUser.isAccessStringOn("off"));
        assertFalse(ElastiCacheUser.isAccessStringOn("on ~* off"));
        assertFalse(ElastiCacheUser.isAccessStringOn("~* +@all"));
        assertFalse(ElastiCacheUser.isAccessStringOn(null));
        assertFalse(ElastiCacheUser.isAccessStringOn(""));
        assertFalse(ElastiCacheUser.isAccessStringOn("   "));
    }

    @Test
    void userIsEnabledReflectsAccessString() {
        ElastiCacheUser enabledUser = new ElastiCacheUser();
        enabledUser.setAccessString("on ~* +@all");
        assertTrue(enabledUser.isEnabled());

        ElastiCacheUser disabledUser = new ElastiCacheUser();
        disabledUser.setAccessString("off -@all");
        assertFalse(disabledUser.isEnabled());
    }
}
