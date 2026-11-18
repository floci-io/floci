package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudtrail.model.Trail;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * IAM's lookup reads the tags of the trail the ARN names, as AddTags and RemoveTags leave them, a tag with no value
     * as the empty string, and nothing for another account's, another Region's or an unknown trail.
     */
    @Test
    void storedTagsAreTheTagsOfTheTrailTheArnNames() {
        String arn = service.createTrail(REGION, "tagged", "trail-bucket", null, TOPIC, true, false, false, false,
                Map.of("team", "web", "env", "dev")).trailArn();
        Map<String, String> unvalued = new HashMap<>();
        unvalued.put("team", "data");
        unvalued.put("reviewed", null);
        service.addTags(arn, unvalued);
        service.removeTags(arn, List.of("env"));

        assertEquals("cloudtrail", service.namespace());
        assertEquals(Map.of("team", "data", "reviewed", ""), service.storedTags(arn));
        assertTrue(service.storedTags(arn.replace(ACCOUNT, "111122223333")).isEmpty());
        assertTrue(service.storedTags(arn.replace(REGION, "us-west-2")).isEmpty());
        assertTrue(service.storedTags(arn.replace("trail/tagged", "trail/missing")).isEmpty());
        assertTrue(service.storedTags("arn:aws:s3:::trail-bucket").isEmpty());
    }

    /** A trail stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareTrailInPlace() {
        InMemoryStorage<String, CloudTrailEntry> raw = new InMemoryStorage<>();
        CloudTrailService legacyState = new CloudTrailService(
                new InMemoryStorageFactory(ACCOUNT).withStore("cloudtrail-trails.json", raw),
                new RegionResolver(REGION, ACCOUNT), mock(IamService.class), new ObjectMapper());
        String arn = legacyState.createTrail(REGION, "legacy", "trail-bucket", null, TOPIC, true, false, false, false,
                Map.of("team", "data")).trailArn();
        raw.put(REGION + ":legacy", raw.get(ACCOUNT + "/" + REGION + ":legacy").orElseThrow());
        raw.delete(ACCOUNT + "/" + REGION + ":legacy");

        assertEquals(Map.of("team", "data"), legacyState.storedTags(arn));
        assertEquals(Set.of(REGION + ":legacy"), raw.keys());
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
