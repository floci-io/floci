package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the empty-list read responses for the subnet/parameter group describes, so
 * SDK clients get a valid 200 instead of failing with UnsupportedOperation (400), plus
 * the XML shape of the CacheSubnetGroup CRUD actions.
 */
class ElastiCacheQueryHandlerTest {

    private ElastiCacheQueryHandler handler;
    private ElastiCacheService service;

    @BeforeEach
    void setUp() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        service = mock(ElastiCacheService.class);
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
    void describeCacheSubnetGroups_includesSubnetsAndArn() {
        CacheSubnetGroup group = new CacheSubnetGroup("my-subnet-group", "test group", "vpc-123",
                List.of("subnet-aaa", "subnet-bbb"),
                Map.of("subnet-aaa", "us-east-1a", "subnet-bbb", "us-east-1b"));
        group.setArn("arn:aws:elasticache:us-east-1:123456789012:subnetgroup:my-subnet-group");
        when(service.listCacheSubnetGroups(null)).thenReturn(List.of(group));

        Response response = handler.handle("DescribeCacheSubnetGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheSubnetGroupName>my-subnet-group</CacheSubnetGroupName>"));
        assertTrue(body.contains("<CacheSubnetGroupDescription>test group</CacheSubnetGroupDescription>"));
        assertTrue(body.contains("<VpcId>vpc-123</VpcId>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-aaa</SubnetIdentifier>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-bbb</SubnetIdentifier>"));
        assertTrue(body.contains("<ARN>arn:aws:elasticache:us-east-1:123456789012:subnetgroup:my-subnet-group</ARN>"));
    }

    @Test
    void createCacheSubnetGroup_passesSubnetMembersToService() {
        CacheSubnetGroup group = new CacheSubnetGroup("sample-group", "desc", "vpc-123",
                List.of("subnet-aaa", "subnet-bbb"), Map.of());
        when(service.createCacheSubnetGroup(eq("sample-group"), eq("desc"),
                eq(List.of("subnet-aaa", "subnet-bbb")), isNull(), eq(Map.of())))
                .thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("CacheSubnetGroupName", "sample-group");
        p.add("CacheSubnetGroupDescription", "desc");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-aaa");
        p.add("SubnetIds.SubnetIdentifier.2", "subnet-bbb");

        Response response = handler.handle("CreateCacheSubnetGroup", p);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheSubnetGroupName>sample-group</CacheSubnetGroupName>"));
    }

    @Test
    void createCacheSubnetGroup_missingNameReturnsMissingParameter() {
        Response response = handler.handle("CreateCacheSubnetGroup", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("MissingParameter"));
    }

    @Test
    void deleteCacheSubnetGroup_notFoundPropagatesFault() {
        MultivaluedMap<String, String> p = params();
        p.add("CacheSubnetGroupName", "missing-group");
        org.mockito.Mockito.doThrow(new AwsException("CacheSubnetGroupNotFoundFault",
                        "Cache subnet group missing-group not found.", 404))
                .when(service).deleteCacheSubnetGroup("missing-group");

        Response response = handler.handle("DeleteCacheSubnetGroup", p);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("CacheSubnetGroupNotFoundFault"));
    }

    @Test
    void modifyCacheSubnetGroup_passesSubnetMembersToService() {
        CacheSubnetGroup group = new CacheSubnetGroup("sample-group", "desc", "vpc-123",
                List.of("subnet-ccc"), Map.of());
        when(service.modifyCacheSubnetGroup(eq("sample-group"), any(), eq(List.of("subnet-ccc")), isNull()))
                .thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("CacheSubnetGroupName", "sample-group");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-ccc");

        Response response = handler.handle("ModifyCacheSubnetGroup", p);

        assertEquals(200, response.getStatus());
        verify(service).modifyCacheSubnetGroup(eq("sample-group"), any(), eq(List.of("subnet-ccc")), isNull());
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
