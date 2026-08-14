package io.github.hectorvent.floci.services.kinesisanalytics.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link FlinkContainerManager}'s {@code application_properties.json} construction —
 * the JSON shape must exactly match real MSF/KDA's runtime file so a real
 * {@code KinesisAnalyticsRuntime.getApplicationProperties()} call in a user's JAR finds it. The
 * container-injection path itself (tar-entry-prefix trick to create the non-existent {@code /etc/flink}
 * directory via the daemon-level copy) was verified live against a real {@code apache/flink} image
 * rather than re-implemented here with a mocked DockerClient — see the design notes on the injection
 * call sites for what was checked and why.
 */
class FlinkContainerManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlinkContainerManager manager() {
        return new FlinkContainerManager(
                Mockito.mock(ContainerBuilder.class),
                Mockito.mock(ContainerLifecycleManager.class),
                Mockito.mock(ContainerLogStreamer.class),
                Mockito.mock(ContainerDetector.class),
                Mockito.mock(EmulatorConfig.class),
                Mockito.mock(RegionResolver.class),
                Mockito.mock(S3Service.class),
                Mockito.mock(FlinkRestClient.class),
                MAPPER);
    }

    @Test
    void applicationPropertiesJsonMatchesTheRealMsfFileShape() throws Exception {
        FlinkApplication app = new FlinkApplication("demo", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.put("ProducerConfigProperties", Map.of("flink.stream.initpos", "LATEST", "aws.region", "us-west-2"));
        groups.put("ConsumerConfigProperties", Map.of("aws.region", "us-west-2"));
        app.setEnvironmentProperties(groups);

        byte[] json = manager().applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        assertTrue(root.isArray());
        assertEquals(2, root.size());
        assertEquals("ProducerConfigProperties", root.get(0).get("PropertyGroupId").asText());
        assertEquals("LATEST", root.get(0).get("PropertyMap").get("flink.stream.initpos").asText());
        assertEquals("us-west-2", root.get(0).get("PropertyMap").get("aws.region").asText());
        assertEquals("ConsumerConfigProperties", root.get(1).get("PropertyGroupId").asText());
    }

    @Test
    void applicationPropertiesJsonIsAnEmptyArrayWhenNoPropertiesConfigured() throws Exception {
        FlinkApplication app = new FlinkApplication("bare", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/bare",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");

        byte[] json = manager().applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        // Real MSF always provides the file, even with zero property groups configured, so
        // KinesisAnalyticsRuntime.getApplicationProperties() never has to handle a missing file.
        assertTrue(root.isArray());
        assertEquals(0, root.size());
    }
}
