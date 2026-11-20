package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * DynamoDB in {@code wal} mode with its items in the {@code per-table} layout, split into four
 * shards, on a directory of its own, and the settings of two other classes that share the
 * application. Every other service stays on the test default, memory. Quarkus builds the
 * application once per distinct profile class, and keeps every one it built for the rest of the
 * test JVM, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.storage.services.dynamodb.mode=wal}, {@code item-layout=per-table},
 *   {@code item-shards=4} and {@code floci.storage.persistent-path=}{@value #STORAGE_DIR}.
 *   {@code DynamoDbPartitionedWalStorageIntegrationTest} relies on all of it: it checks that the
 *   configuration reaches the item store and that the item store writes the journal and shards.</li>
 *   <li>{@code floci.services.ec2.mock-public-addresses=documentation} gives a mock instance due an
 *   automatic public address one from 203.0.113.0/24. {@code Ec2MockPublicAddressIntegrationTest}
 *   relies on it.</li>
 *   <li>{@code EXTRA_CORS_ALLOWED_ORIGINS=http://localhost:4000}, the environment-style alias of
 *   {@code floci.security.extra-cors-allowed-origins}, allows that one extra CORS origin.
 *   {@code GlobalCorsFilterAliasIntegrationTest} relies on it, to check the alias.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes: DynamoDB storage only reaches DynamoDB requests,
 * the address range only an EC2 instance due an automatic public address, and the extra origin only
 * a request that sends that {@code Origin}, and no other class here makes such a request. The
 * persistent path moves where this application writes what services keep on disk whatever their
 * storage mode, each under its own directory.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class DynamoDbPartitionedWalStorageProfile implements QuarkusTestProfile {

    public static final String STORAGE_DIR = "target/dynamodb-partitioned-wal-it";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.storage.services.dynamodb.mode", "wal",
                "floci.storage.services.dynamodb.item-layout", "per-table",
                "floci.storage.services.dynamodb.item-shards", "4",
                "floci.storage.persistent-path", STORAGE_DIR,
                "floci.services.ec2.mock-public-addresses", "documentation",
                "EXTRA_CORS_ALLOWED_ORIGINS", "http://localhost:4000");
    }
}
