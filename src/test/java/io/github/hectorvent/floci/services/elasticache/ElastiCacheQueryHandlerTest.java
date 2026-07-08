package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies the empty-list read responses for the subnet/parameter group describes, so
 * SDK clients get a valid 200 instead of failing with UnsupportedOperation (400).
 */
class ElastiCacheQueryHandlerTest {

    private ElastiCacheQueryHandler handler;

    @BeforeEach
    void setUp() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        ElastiCacheService service = mock(ElastiCacheService.class);
        ElastiCacheMemcachedService memcachedService = mock(ElastiCacheMemcachedService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        handler = new ElastiCacheQueryHandler(sigV4Validator, service, memcachedService, regionResolver);
    }

    @Test
    void describeCacheSubnetGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheSubnetGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheSubnetGroupsResult><CacheSubnetGroups></CacheSubnetGroups></DescribeCacheSubnetGroupsResult>"),
                "Expected empty CacheSubnetGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeCacheParameterGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheParameterGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheParameterGroupsResult><CacheParameterGroups></CacheParameterGroups></DescribeCacheParameterGroupsResult>"),
                "Expected empty CacheParameterGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void unsupportedOperationStillReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("UnsupportedOperation"));
    }

    private static MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }
}
