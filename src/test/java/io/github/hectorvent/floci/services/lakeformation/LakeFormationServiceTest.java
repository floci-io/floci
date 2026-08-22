package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lakeformation.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LakeFormationServiceTest {

    @Mock
    private LakeFormationStorage storage;

    @Mock
    private RegionResolver regionResolver;

    private LakeFormationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(regionResolver.getAccountId()).thenReturn("123456789012");
        service = new LakeFormationService(storage, regionResolver);
    }

    @Test
    void testPutDataLakeSettings() {
        PutDataLakeSettingsRequest req = new PutDataLakeSettingsRequest();
        req.setCatalogId("custom-catalog");
        DataLakeSettings settings = new DataLakeSettings();
        req.setDataLakeSettings(settings);

        PutDataLakeSettingsResponse res = service.putDataLakeSettings(req);
        
        assertNotNull(res);
        verify(storage).putDataLakeSettings("custom-catalog", settings);
    }

    @Test
    void testRegisterResource() {
        RegisterResourceRequest req = new RegisterResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        req.setRoleArn("arn:aws:iam::123:role/MyRole");
        req.setUseServiceLinkedRole(true);

        RegisterResourceResponse res = service.registerResource(req);
        
        assertNotNull(res);
        verify(storage).registerResource("arn:aws:s3:::my-bucket", "arn:aws:iam::123:role/MyRole", true, null);
    }

    @Test
    void testRegisterResourceMissingArn() {
        RegisterResourceRequest req = new RegisterResourceRequest();
        
        AwsException ex = assertThrows(AwsException.class, () -> service.registerResource(req));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void testDescribeResource() {
        DescribeResourceRequest req = new DescribeResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        
        ResourceInfo info = new ResourceInfo();
        info.setResourceArn("arn:aws:s3:::my-bucket");
        when(storage.describeResource("arn:aws:s3:::my-bucket")).thenReturn(Optional.of(info));

        DescribeResourceResponse res = service.describeResource(req);
        assertNotNull(res.getResourceInfo());
        assertEquals("arn:aws:s3:::my-bucket", res.getResourceInfo().getResourceArn());
    }

    @Test
    void testDescribeResourceNotFound() {
        DescribeResourceRequest req = new DescribeResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        when(storage.describeResource(anyString())).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () -> service.describeResource(req));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void testCreateLFTag() {
        CreateLFTagRequest req = new CreateLFTagRequest();
        req.setTagKey("my-tag");
        req.setTagValues(List.of("val1", "val2"));

        CreateLFTagResponse res = service.createLFTag(req);
        assertNotNull(res);
        
        verify(storage).createLFTag("123456789012", "my-tag", List.of("val1", "val2"));
    }

    @Test
    void testGetLFTag() {
        GetLFTagRequest req = new GetLFTagRequest();
        req.setTagKey("my-tag");

        LFTag tag = new LFTag();
        tag.setTagKey("my-tag");
        tag.setTagValues(List.of("val1"));
        when(storage.getLFTag("123456789012", "my-tag")).thenReturn(Optional.of(tag));

        GetLFTagResponse res = service.getLFTag(req);
        assertEquals("123456789012", res.getCatalogId());
        assertEquals("my-tag", res.getTagKey());
        assertEquals(List.of("val1"), res.getTagValues());
    }
}
