package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * DynamoDB, ACM and S3 on persistent storage while every other service stays on the test default,
 * memory, for the classes that test a per-service storage mode. Quarkus builds the application
 * once per distinct profile class, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.storage.services.dynamodb.mode=persistent} puts DynamoDB's table file on disk.
 *   {@code StorageFactoryDuplicateBackendTest} relies on it: it writes that file through two
 *   backends and reads it back.</li>
 *   <li>{@code floci.storage.services.acm.mode=persistent} puts ACM's store on disk.
 *   {@code StorageFactoryServiceCatalogIntegrationTest} relies on it: it checks that an ACM store
 *   written to reaches disk.</li>
 *   <li>{@code floci.storage.services.s3.mode=persistent} keeps S3 objects in files, which
 *   CloudFront streams to the viewer. {@code CloudFrontS3DiskOriginServingTest} relies on it: it
 *   checks that its object is a file before fetching it through a distribution.</li>
 *   <li>{@code floci.storage.persistent-path=}{@value #STORAGE_DIR} is the directory those files go
 *   in, where each class looks for its own. All three rely on it.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes, since each reaches a single service and each
 * class is the only one here that calls its service. {@code StorageFactoryDuplicateBackendTest}
 * clears every store before and after it runs, and writes placeholder strings into DynamoDB's
 * table store in between, so no other class here may call DynamoDB, and none may count on state
 * outliving its own class.
 *
 * <p>Each class also deletes what it creates, so a class that joins later finds nothing left
 * behind. A class joins only if no other class here calls its service, and it deletes what it
 * creates.
 */
public class PerServicePersistentStorageProfile implements QuarkusTestProfile {

    public static final String STORAGE_DIR = "target/per-service-persistent-storage-it";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.storage.services.dynamodb.mode", "persistent",
                "floci.storage.services.acm.mode", "persistent",
                "floci.storage.services.s3.mode", "persistent",
                "floci.storage.persistent-path", STORAGE_DIR);
    }
}
