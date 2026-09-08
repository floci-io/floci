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

        PutDataLakeSettingsResponse res = service.putDataLakeSettings("us-east-1", req);
        
        assertNotNull(res);
        verify(storage).putDataLakeSettings("us-east-1", "custom-catalog", settings);
    }

    @Test
    void testRegisterResource() {
        RegisterResourceRequest req = new RegisterResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        req.setRoleArn("arn:aws:iam::123:role/MyRole");
        req.setUseServiceLinkedRole(true);

        when(storage.describeResource(anyString(), anyString())).thenReturn(Optional.empty());

        RegisterResourceResponse res = service.registerResource("us-east-1", req);
        
        assertNotNull(res);
        verify(storage).registerResource("us-east-1", "arn:aws:s3:::my-bucket", "arn:aws:iam::123:role/MyRole", true, null);
    }

    @Test
    void testRegisterResourceAlreadyExists() {
        RegisterResourceRequest req = new RegisterResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");

        when(storage.describeResource("us-east-1", "arn:aws:s3:::my-bucket")).thenReturn(Optional.of(new ResourceInfo()));

        AwsException ex = assertThrows(AwsException.class, () -> service.registerResource("us-east-1", req));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
    }

    @Test
    void testRegisterResourceMissingArn() {
        RegisterResourceRequest req = new RegisterResourceRequest();
        
        AwsException ex = assertThrows(AwsException.class, () -> service.registerResource("us-east-1", req));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void testUpdateResource() {
        UpdateResourceRequest req = new UpdateResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        req.setRoleArn("arn:aws-us-gov:iam::123:role/UpdatedRole");
        req.setHybridAccessEnabled(true);

        UpdateResourceResponse res = service.updateResource("us-east-1", req);

        assertNotNull(res);
        verify(storage).updateResource(
            "us-east-1", "arn:aws:s3:::my-bucket", "arn:aws-us-gov:iam::123:role/UpdatedRole", null, true, null);
    }

    @Test
    void testUpdateResourceNotFound() {
        UpdateResourceRequest req = new UpdateResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        req.setRoleArn("arn:aws:iam::123:role/UpdatedRole");
        doThrow(new AwsException("EntityNotFoundException", "Resource not found", 400))
            .when(storage).updateResource(anyString(), anyString(), anyString(), any(), any(), any());

        AwsException ex = assertThrows(AwsException.class, () -> service.updateResource("us-east-1", req));

        assertEquals("EntityNotFoundException", ex.getErrorCode());
        verify(storage).updateResource(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void testUpdateResourceRejectsInvalidRequiredArn() {
        UpdateResourceRequest req = new UpdateResourceRequest();
        req.setResourceArn("not-an-arn");
        req.setRoleArn("not-an-arn");

        AwsException ex = assertThrows(AwsException.class, () -> service.updateResource("us-east-1", req));

        assertEquals("InvalidInputException", ex.getErrorCode());
        verifyNoInteractions(storage);
    }

    @Test
    void testUpdateResourceRejectsMalformedResourceArn() {
        UpdateResourceRequest req = new UpdateResourceRequest();
        req.setResourceArn("arn:aws:s3");
        req.setRoleArn("arn:aws:iam::123:role/UpdatedRole");

        AwsException ex = assertThrows(AwsException.class, () -> service.updateResource("us-east-1", req));

        assertEquals("InvalidInputException", ex.getErrorCode());
        verifyNoInteractions(storage);
    }

    @Test
    void testDescribeResource() {
        DescribeResourceRequest req = new DescribeResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        
        ResourceInfo info = new ResourceInfo();
        info.setResourceArn("arn:aws:s3:::my-bucket");
        when(storage.describeResource("us-east-1", "arn:aws:s3:::my-bucket")).thenReturn(Optional.of(info));

        DescribeResourceResponse res = service.describeResource("us-east-1", req);
        assertNotNull(res.getResourceInfo());
        assertEquals("arn:aws:s3:::my-bucket", res.getResourceInfo().getResourceArn());
    }

    @Test
    void testDescribeResourceNotFound() {
        DescribeResourceRequest req = new DescribeResourceRequest();
        req.setResourceArn("arn:aws:s3:::my-bucket");
        when(storage.describeResource(anyString(), anyString())).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () -> service.describeResource("us-east-1", req));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void testCreateLFTag() {
        CreateLFTagRequest req = new CreateLFTagRequest();
        req.setTagKey("my-tag");
        req.setTagValues(List.of("val1", "val2"));
        when(storage.getLFTag(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        CreateLFTagResponse res = service.createLFTag("us-east-1", req);
        assertNotNull(res);
        
        verify(storage).createLFTag("us-east-1", "123456789012", "my-tag", List.of("val1", "val2"));
    }

    @Test
    void testCreateLFTagAlreadyExists() {
        CreateLFTagRequest req = new CreateLFTagRequest();
        req.setTagKey("my-tag");
        req.setTagValues(List.of("val1"));

        when(storage.getLFTag("us-east-1", "123456789012", "my-tag")).thenReturn(Optional.of(new LFTag()));

        AwsException ex = assertThrows(AwsException.class, () -> service.createLFTag("us-east-1", req));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
    }

    @Test
    void testGetLFTag() {
        GetLFTagRequest req = new GetLFTagRequest();
        req.setTagKey("my-tag");

        LFTag tag = new LFTag();
        tag.setTagKey("my-tag");
        tag.setTagValues(List.of("val1"));
        when(storage.getLFTag("us-east-1", "123456789012", "my-tag")).thenReturn(Optional.of(tag));

        GetLFTagResponse res = service.getLFTag("us-east-1", req);
        assertEquals("123456789012", res.getCatalogId());
        assertEquals("my-tag", res.getTagKey());
        assertEquals(List.of("val1"), res.getTagValues());
    }
}
