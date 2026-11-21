package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudfront.model.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFrontKeyValueStoreEtagTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleIssuedVersionsFailPreconditionsWithoutChangingControlOrDataState(boolean delete) {
        CloudFrontKeyValueStoreService service = service();
        KeyValueStore first = service.create("versions", "before");
        KeyValueStore data = service.putKey(first.getArn(), first.getDataEtag(), "key", "value");
        KeyValueStore current = service.update(first.getName(), first.getEtag(), "after");

        if (delete) {
            assertError("PreconditionFailed", 412, () -> service.delete("versions", first.getEtag()));
        } else {
            assertError("PreconditionFailed", 412, () -> service.update("versions", first.getEtag(), "lost"));
        }
        assertEquals("after", service.describe("versions").getComment());
        assertEquals(current.getEtag(), service.describe("versions").getEtag());
        assertEquals(data.getDataEtag(), service.describeByArn(first.getArn()).getDataEtag());
        assertEquals("value", service.getKey(first.getArn(), "key").value());
        service.delete("versions", current.getEtag());
        assertError("EntityNotFound", 404, () -> service.describe("versions"));
    }

    @Test
    void missingEmptyAndMalformedVersionsKeepTheInvalidIfMatchError() {
        CloudFrontKeyValueStoreService service = service();
        KeyValueStore current = service.create("versions", "unchanged");
        assertError("InvalidIfMatchVersion", 400, () -> service.update("versions", null, "lost"));
        assertError("InvalidIfMatchVersion", 400, () -> service.delete("versions", null));
        for (String malformed : List.of("", "stale", "not-a-version", " " + current.getEtag(), current.getEtag() + " ")) {
            assertError("InvalidIfMatchVersion", 400, () -> service.update("versions", malformed, "lost"));
            assertError("InvalidIfMatchVersion", 400, () -> service.delete("versions", malformed));
        }
        assertEquals("unchanged", service.describe("versions").getComment());
        assertEquals(current.getEtag(), service.describe("versions").getEtag());
    }

    @Test
    void matchingStoredVersionsRemainValidWithAnOpaqueFormat() {
        CloudFrontKeyValueStoreService service = service();
        KeyValueStore existing = service.create("versions", "before");
        existing.setEtag("opaque-stored-version");
        KeyValueStore updated = service.update("versions", existing.getEtag(), "after");
        assertEquals("after", updated.getComment());
        updated.setEtag("another-opaque-version");
        service.delete("versions", updated.getEtag());
        assertError("EntityNotFound", 404, () -> service.describe("versions"));
    }

    private static CloudFrontKeyValueStoreService service() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any()))
                .thenAnswer(call -> AccountAwareStorageBackend.inMemory("000000000000"));
        return new CloudFrontKeyValueStoreService(factory, new RegionResolver("us-east-1", "000000000000"));
    }

    private static void assertError(String code, int status, Runnable action) {
        AwsException failure = assertThrows(AwsException.class, action::run);
        assertEquals(code, failure.getErrorCode());
        assertEquals(status, failure.getHttpStatus());
    }
}
