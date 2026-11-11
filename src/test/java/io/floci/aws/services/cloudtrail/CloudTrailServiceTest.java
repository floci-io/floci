package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudtrail.model.Trail;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudTrailServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:trail-topic";

    private CloudTrailService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        service = new CloudTrailService(storageFactory, new RegionResolver(REGION, ACCOUNT),
                mock(IamService.class), new ObjectMapper());
    }

    private Trail trailWithPrefix(String name, String prefix) {
        return service.createTrail(REGION, name, "trail-bucket", prefix, TOPIC, true, false, false, false);
    }

    // --- replaceTrailS3KeyPrefix: a service method that does not exist on main ---

    @Test
    void replaceTrailS3KeyPrefixStoresTheGivenValueAndReturnsTheUpdatedTrail() {
        trailWithPrefix("replace-set", "old/");

        Trail returned = service.replaceTrailS3KeyPrefix(REGION, "replace-set", "new/");

        assertEquals("new/", returned.s3KeyPrefix());
        assertEquals("new/", service.getTrail(REGION, "replace-set").s3KeyPrefix());
    }

    @Test
    void replaceTrailS3KeyPrefixWithNullClearsTheStoredValue() {
        trailWithPrefix("replace-null", "old/");

        Trail returned = service.replaceTrailS3KeyPrefix(REGION, "replace-null", null);

        assertNull(returned.s3KeyPrefix());
        assertNull(service.getTrail(REGION, "replace-null").s3KeyPrefix());
        assertNull(service.describeTrails(REGION, List.of("replace-null")).get(0).s3KeyPrefix());
    }

    @Test
    void replaceTrailS3KeyPrefixStoresTheEmptyStringAsGiven() {
        trailWithPrefix("replace-empty", "old/");

        service.replaceTrailS3KeyPrefix(REGION, "replace-empty", "");

        assertEquals("", service.getTrail(REGION, "replace-empty").s3KeyPrefix());
    }

    @Test
    void replaceTrailS3KeyPrefixAcceptsTheTrailArn() {
        Trail created = trailWithPrefix("replace-arn", "old/");

        service.replaceTrailS3KeyPrefix(REGION, created.trailArn(), null);

        assertNull(service.getTrail(REGION, "replace-arn").s3KeyPrefix());
    }

    @Test
    void replaceTrailS3KeyPrefixLeavesTheBucketAndTheTopicUntouched() {
        trailWithPrefix("replace-others", "old/");

        service.replaceTrailS3KeyPrefix(REGION, "replace-others", null);

        Trail stored = service.getTrail(REGION, "replace-others");
        assertEquals("trail-bucket", stored.s3BucketName());
        assertEquals(TOPIC, stored.snsTopicArn());
    }

    @Test
    void replaceTrailS3KeyPrefixOnAnUnknownTrailThrowsWhatUpdateTrailThrows() {
        AwsException fromUpdate = assertThrows(AwsException.class, () -> service.updateTrail(REGION, "no-such-trail",
                null, "x/", null, null, null, null, null));

        AwsException fromReplace = assertThrows(AwsException.class,
                () -> service.replaceTrailS3KeyPrefix(REGION, "no-such-trail", "x/"));

        assertEquals(fromUpdate.getErrorCode(), fromReplace.getErrorCode());
        assertEquals(fromUpdate.getHttpStatus(), fromReplace.getHttpStatus());
    }

    // --- direct UpdateTrail without a prefix keeps it: unchanged from main ---

    @Test
    void updateTrailWithoutAnS3KeyPrefixKeepsTheStoredPrefix() {
        trailWithPrefix("update-keeps", "kept/");

        service.updateTrail(REGION, "update-keeps", "other-bucket", null, null, null, null, null, null);

        Trail stored = service.getTrail(REGION, "update-keeps");
        assertEquals("kept/", stored.s3KeyPrefix());
        assertEquals("other-bucket", stored.s3BucketName());
    }
}
