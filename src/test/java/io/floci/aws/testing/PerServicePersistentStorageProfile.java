package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * DynamoDB, ACM and S3 on persistent storage while every other service stays on the test default,
 * memory, for the classes that test a per-service storage mode, and the settings of two other
 * classes that share the application. Quarkus builds the application once per distinct profile
 * class, and keeps every one it built for the rest of the test JVM, so they share this one rather
 * than each declaring their own.
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
 *   <li>{@code floci.services.ecs.host-volume-roots=/dev/fuse} lets a task definition name the host
 *   path {@code /dev/fuse}, which Floci otherwise refuses.
 *   {@code CloudFormationEcsLinuxParametersIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.iam.enabled=false} turns IAM off, and STS with it.
 *   {@code IamStsSharedEnablementIntegrationTest} relies on it.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes, since each reaches a single service and each
 * class is the only one here that calls its service. No class here calls IAM or STS or registers
 * an ECS task definition apart from the two that rely on those settings, and the persistent path
 * moves where this application writes what services keep on disk whatever their storage mode,
 * Timestream backups and Lambda code-volume markers among them, each under its own directory. {@code StorageFactoryDuplicateBackendTest}
 * clears every store before and after it runs, and writes placeholder strings into DynamoDB's
 * table store in between, so no other class here may call DynamoDB, and none may count on state
 * outliving its own class.
 *
 * <p>Each class also deletes what it creates, so a class that joins later finds nothing left
 * behind.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here. It must also delete what it creates and must not call DynamoDB.
 */
public class PerServicePersistentStorageProfile implements QuarkusTestProfile {

    public static final String STORAGE_DIR = "target/per-service-persistent-storage-it";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.storage.services.dynamodb.mode", "persistent",
                "floci.storage.services.acm.mode", "persistent",
                "floci.storage.services.s3.mode", "persistent",
                "floci.storage.persistent-path", STORAGE_DIR,
                "floci.services.ecs.host-volume-roots", "/dev/fuse",
                "floci.services.iam.enabled", "false");
    }
}
