package io.github.hectorvent.floci.testutil;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;

import java.lang.reflect.Constructor;

public final class IamServiceTestHelper {

    private IamServiceTestHelper() {
    }

    @SuppressWarnings("unchecked")
    public static IamService iamServiceWithAccessKey(String accessKeyId, String secretAccessKey) {
        try {
            Constructor<IamService> constructor = IamService.class.getDeclaredConstructor(
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    String.class
            );
            constructor.setAccessible(true);

            InMemoryStorage<String, AccessKey> accessKeys = new InMemoryStorage<>();
            accessKeys.put(accessKeyId, new AccessKey(accessKeyId, secretAccessKey, "test-user"));

            return constructor.newInstance(
                    null,
                    null,
                    null,
                    null,
                    accessKeys,
                    null,
                    "123456789012"
            );
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct IamService test fixture", e);
        }
    }
}
