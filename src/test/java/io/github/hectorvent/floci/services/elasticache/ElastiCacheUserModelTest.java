package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
