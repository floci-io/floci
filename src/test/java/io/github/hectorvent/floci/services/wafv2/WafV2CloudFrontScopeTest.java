package io.github.hectorvent.floci.services.wafv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CloudFormation calls the service outside any request, where the request region falls back to
 * the deployment default. The CLOUDFRONT scope must follow the region the caller passes, so a
 * GovCloud default neither refuses a commercial stack's WebACL nor admits a GovCloud one.
 */
class WafV2CloudFrontScopeTest {

    private WafV2Service service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new WafV2Service(storageFactory, new RegionResolver("us-gov-west-1", "000000000000"),
                new ObjectMapper());
    }

    @Test
    void aCommercialStackCreatesItsCloudFrontWebAclUnderAGovCloudDefault() {
        WebAcl acl = service.createWebAcl(new WebAcl(), "CLOUDFRONT", "edge", "us-east-1");

        assertTrue(acl.getArn().startsWith("arn:aws:wafv2:us-east-1:000000000000:global/webacl/edge/"),
                acl.getArn());
        assertEquals("edge", service.getWebAcl("CLOUDFRONT", acl.getId(), "edge").getName());
        service.deleteWebAcl("CLOUDFRONT", acl.getId(), "edge", acl.getLockToken());
    }

    @Test
    void aGovCloudStackIsRefusedTheCloudFrontScope() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.createWebAcl(new WebAcl(), "CLOUDFRONT", "edge", "us-gov-west-1"));

        assertEquals("WAFInvalidParameterException", error.getErrorCode());
    }
}
