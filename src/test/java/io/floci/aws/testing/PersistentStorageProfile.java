package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Persistent storage as every service's default, in one directory, for the classes that read back
 * the file a service writes. Quarkus builds the application once per distinct profile class, so
 * they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.storage.mode=persistent} writes each service's state to a JSON file as it
 *   changes. A service the test {@code application.yml} gives a mode of its own keeps that mode:
 *   Resource Explorer stays in memory. {@code AthenaCreateWorkGroupPersistenceIntegrationTest},
 *   {@code CloudFormationPersistenceIntegrationTest} and
 *   {@code GlueSecurityConfigurationIntegrationTest} each read their service's file back, and rely
 *   on it.</li>
 *   <li>{@code floci.storage.persistent-path=}{@value #STORAGE_DIR} puts those files in the one
 *   directory each class reads its own from. All three rely on it.</li>
 * </ul>
 *
 * <p>The classes can share the directory because each one deletes and reads only its own
 * service's file ({@code workgroups.json}, {@code cloudformation-stacks.json} and
 * {@code security_configurations.json}), and is the only class here that calls its service. The
 * CloudFormation stack also creates an S3 bucket, and no other class here calls S3.
 *
 * <p>Each class also deletes what it creates, so a class that joins later finds nothing left
 * behind, in memory or on disk. A class joins only if no other class here calls its service, and
 * it deletes what it creates.
 */
public class PersistentStorageProfile implements QuarkusTestProfile {

    public static final String STORAGE_DIR = "target/persistent-storage-it";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.storage.mode", "persistent",
                "floci.storage.persistent-path", STORAGE_DIR);
    }
}
