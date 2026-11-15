package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.KinesisCfnProvisioner;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContexts;
import io.floci.aws.services.kinesis.model.KinesisStream;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KinesisCfnServiceTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void updateReconcilesRetentionAndTagsBeforeChangingStreamMode() throws Exception {
        MutableClock clock = new MutableClock();
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), clock, 0, 60_000);
        KinesisStream stream = service.createStream("s", 1, REGION);
        service.addTagsToStream("s", Map.of("stale", "1"), REGION);

        provision(service, """
                {"Name": "s", "RetentionPeriodHours": "48",
                 "StreamModeDetails": {"StreamMode": "ON_DEMAND"},
                 "Tags": [{"Key": "fresh", "Value": "2"}]}
                """, "s");

        assertEquals(48, stream.getRetentionPeriodHours());
        assertEquals(Map.of("fresh", "2"), stream.getTags());
        assertEquals("ON_DEMAND", stream.getStreamMode());
        assertEquals("UPDATING", stream.getStreamStatus(clock.instant()));
    }

    @Test
    void createPersistsInitialTagsAndRetentionBeforeTheStreamBecomesActive() throws Exception {
        Path path = tempDir.resolve("cfn-kinesis-streams.json");
        TypeReference<Map<String, KinesisStream>> type = new TypeReference<>() {};
        PersistentStorage<String, KinesisStream> store = new PersistentStorage<>(path, type);
        MutableClock clock = new MutableClock();
        KinesisService service = new KinesisService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), clock, 60_000, 0);

        provision(service, """
                {"Name": "s", "RetentionPeriodHours": "48", "Tags": [{"Key": "env", "Value": "dev"}]}
                """, null);

        PersistentStorage<String, KinesisStream> reloaded = new PersistentStorage<>(path, type);
        reloaded.load();
        KinesisStream restored = reloaded.get(REGION + "::s").orElseThrow();
        assertEquals("CREATING", restored.getStreamStatus(clock.instant()));
        assertEquals(Map.of("env", "dev"), restored.getTags());
        assertEquals(48, restored.getRetentionPeriodHours());
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 9000})
    void createRejectsOutOfRangeRetentionWithoutLeavingStream(int retentionHours) throws Exception {
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));

        AwsException error = assertThrows(AwsException.class, () -> provision(service, """
                {"Name": "s", "RetentionPeriodHours": "%d"}
                """.formatted(retentionHours), null));

        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(0, service.listStreamDetails(REGION).size());
    }

    @Test
    void unchangedStreamModeDoesNotRequireActiveDuringCreatingWindow() throws Exception {
        MutableClock clock = new MutableClock();
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), clock, 60_000, 0);
        KinesisStream stream = service.createStream("s", 1, REGION);

        provision(service, """
                {"Name": "s"}
                """, "s");

        assertEquals("CREATING", stream.getStreamStatus(clock.instant()));
    }

    @Test
    void retentionOnlyUpdateCannotMutateCreatingStream() throws Exception {
        MutableClock clock = new MutableClock();
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), clock, 60_000, 0);
        KinesisStream stream = service.createStream("s", 1, REGION);

        AwsException error = assertThrows(AwsException.class, () -> provision(service, """
                {"Name": "s", "RetentionPeriodHours": "48"}
                """, "s"));

        assertEquals("ResourceInUseException", error.getErrorCode());
        assertEquals(24, stream.getRetentionPeriodHours());
        assertEquals("CREATING", stream.getStreamStatus(clock.instant()));
    }

    @Test
    void invalidUpdateModeLeavesExistingTagsUntouched() throws Exception {
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
        KinesisStream stream = service.createStream("s", 1, REGION);
        service.addTagsToStream("s", Map.of("old", "1"), REGION);

        AwsException error = assertThrows(AwsException.class, () -> provision(service, """
                {"Name": "s", "StreamModeDetails": {"StreamMode": "INVALID"},
                 "Tags": [{"Key": "new", "Value": "2"}]}
                """, "s"));

        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(Map.of("old", "1"), stream.getTags());
        assertEquals("PROVISIONED", stream.getStreamMode());
    }

    private static void provision(KinesisService service, String json, String priorPhysicalId) throws Exception {
        StackResource resource = new StackResource();
        resource.setLogicalId("Stream");
        resource.setResourceType("AWS::Kinesis::Stream");
        resource.setPhysicalId(priorPhysicalId);
        resource.setAttributes(new HashMap<>());
        new KinesisCfnProvisioner(service).provision(resource, MAPPER.readTree(json),
                new ProvisionContext(ProvisionContexts.passthroughEngine(), REGION, "000000000000",
                        "my-stack", priorPhysicalId));
    }
}
