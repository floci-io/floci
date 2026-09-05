package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resource type Floci has no provisioner for is stubbed: a synthetic physical id, an
 * {@code arn:aws:stub:::} ARN attribute and {@code CREATE_COMPLETE}. Stubbing it is the
 * deliberate behaviour, but doing it silently is not: a green stack that created nothing reads
 * exactly like a green stack that created everything.
 *
 * <p>These tests pin the two things that make the stub observable without changing what it
 * builds: the resource status reason, which reaches DescribeStackEvents through the reason
 * CloudFormationService already copies onto the event, and the warn-level log line.
 */
class CloudFormationUnsupportedResourceTypeTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STACK_NAME = "TestStack";
    private static final String LOGICAL_ID = "MyThing";
    private static final String UNSUPPORTED_TYPE = "AWS::Fake::Thing";

    private static final String STUB_REASON =
            "Resource type AWS::Fake::Thing is not supported by Floci. "
                    + "It was stubbed and nothing was created for it.";
    private static final String STRICT_REASON =
            "Resource type AWS::Fake::Thing is not supported by Floci.";
    private static final String STUB_WARNING =
            "Stubbing unsupported resource type AWS::Fake::Thing (MyThing): nothing is created for it. "
                    + "Set floci.services.cloudformation.allow-stub-unsupported-resource-types=false "
                    + "to fail the stack instead.";
    private static final String DELETE_WARNING =
            "No delete implemented for resource type AWS::Fake::Thing: "
                    + "MyThing-11a79dff is not removed here.";

    private final ObjectMapper mapper = new ObjectMapper();
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        provisioner = CfnProvisionerFixture.builder().objectMapper(mapper).build();
    }

    @Test
    void stubbedResourceCarriesTheUnsupportedTypeReason() {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        StackResource resource = whileCapturingProvisionerLogs(logged, this::provisionUnsupported);

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals(STUB_REASON, resource.getStatusReason());
        assertTrue(logged.stream().anyMatch(r ->
                        r.getLevel().intValue() >= Level.WARNING.intValue()
                                && STUB_WARNING.equals(formatted(r))),
                "expected the stub to be reported at warn, got: " + rendered(logged));
    }

    @Test
    void stubbedResourceStillCarriesTheStubArn() {
        // The stub's shape is a persisted contract: a stack created by an older build carries the
        // synthetic physical id, and the next update has to recognise it.
        StackResource resource = provisionUnsupported();

        assertEquals("arn:aws:stub:::" + LOGICAL_ID, resource.getAttributes().get("Arn"));
        assertTrue(resource.getPhysicalId().startsWith(LOGICAL_ID + "-"),
                "expected the synthetic physical id shape, got: " + resource.getPhysicalId());
    }

    @Test
    void unsupportedTypeFailsTheResourceWhenTheStubIsDisallowed() {
        StackResource resource = provisionerWithStubAllowed(false)
                .provision(LOGICAL_ID, UNSUPPORTED_TYPE, props(), engine(),
                        REGION, ACCOUNT_ID, STACK_NAME);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals(STRICT_REASON, resource.getStatusReason());
        // The failure precedes the synthetic physical id, so Cloud Control's own "no physical id"
        // branch reports the same message instead of a success over a resource that is not there.
        assertNull(resource.getPhysicalId());
    }

    @Test
    void absentConfigMeansTheDocumentedDefault() {
        // A provisioner built in a unit test carries no config, and the knob defaults to true, so
        // absent config has to mean the lenient behaviour. Reading it the other way round makes
        // every config-less provisioner strict.
        StackResource resource = provisionUnsupported();

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals(STUB_REASON, resource.getStatusReason());
        assertNotNull(resource.getPhysicalId());
    }

    @Test
    void deleteOfUnsupportedTypeDoesNotThrow() {
        // The same arm catches a type no provisioner ever created and a type the create switch does
        // provision and this switch does not remove, so the line says what it leaves behind rather
        // than claiming nothing was created.
        List<LogRecord> logged = new CopyOnWriteArrayList<>();

        assertDoesNotThrow(() -> whileCapturingProvisionerLogs(logged, () -> {
            provisioner.delete(UNSUPPORTED_TYPE, LOGICAL_ID + "-11a79dff", REGION);
            return null;
        }));

        assertTrue(logged.stream().anyMatch(r ->
                        r.getLevel().intValue() >= Level.WARNING.intValue()
                                && DELETE_WARNING.equals(formatted(r))),
                "expected the skipped delete to be reported at warn, got: " + rendered(logged));
    }

    private CloudFormationResourceProvisioner provisionerWithStubAllowed(boolean allowed) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.CloudFormationServiceConfig cloudformation =
                mock(EmulatorConfig.CloudFormationServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.cloudformation()).thenReturn(cloudformation);
        when(cloudformation.allowStubUnsupportedResourceTypes()).thenReturn(allowed);
        return CfnProvisionerFixture.builder().objectMapper(mapper).config(config).build();
    }

    private StackResource provisionUnsupported() {
        return provisioner.provision(LOGICAL_ID, UNSUPPORTED_TYPE, props(), engine(),
                REGION, ACCOUNT_ID, STACK_NAME);
    }

    /** Runs {@code action} with a handler on the provisioner's logger, collecting every record. */
    private <T> T whileCapturingProvisionerLogs(List<LogRecord> collected,
                                                java.util.function.Supplier<T> action) {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(CloudFormationResourceProvisioner.class.getName());
        Level original = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                collected.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            return action.get();
        } finally {
            logger.setLevel(original);
            logger.removeHandler(handler);
        }
    }

    /**
     * The record's text. Which logging backend is in play decides whether the parameters are
     * already substituted or still carried alongside the pattern, so substitute them here when
     * they are still there.
     */
    private static String formatted(LogRecord record) {
        Object[] parameters = record.getParameters();
        return parameters == null || parameters.length == 0
                ? record.getMessage()
                : MessageFormat.format(record.getMessage(), parameters);
    }

    private static List<String> rendered(List<LogRecord> records) {
        return records.stream().map(r -> r.getLevel() + " " + formatted(r)).toList();
    }

    private JsonNode props() {
        return mapper.createObjectNode();
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine(
                ACCOUNT_ID, REGION, STACK_NAME, "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }
}
