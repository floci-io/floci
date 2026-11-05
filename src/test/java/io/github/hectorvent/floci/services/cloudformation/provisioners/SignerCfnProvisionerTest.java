package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignerCfnProvisionerTest {

    private static final String PROFILE_ARN_PREFIX = "arn:aws:signer:eu-west-1:000000000000:/signing-profiles/";

    private final SignerCfnProvisioner provisioner = new SignerCfnProvisioner();
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "eu-west-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource profile() {
        StackResource r = new StackResource();
        r.setLogicalId("Profile");
        r.setResourceType("AWS::Signer::SigningProfile");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode props(String profileName) {
        ObjectNode props = mapper.createObjectNode().put("PlatformId", "AWSLambda-SHA384-ECDSA");
        if (profileName != null) {
            props.put("ProfileName", profileName);
        }
        return props;
    }

    @Test
    void namedProfileRefsItsArnAndExposesEveryAttribute() {
        StackResource r = profile();

        provisioner.provision(r, props("my_profile"), ctx(null));

        String arn = PROFILE_ARN_PREFIX + "my_profile";
        String version = r.getAttributes().get("ProfileVersion");
        assertEquals(arn, r.getPhysicalId());
        assertEquals(arn, r.getAttributes().get("Arn"));
        assertEquals("my_profile", r.getAttributes().get("ProfileName"));
        assertTrue(version.matches("[A-Za-z0-9]{10}"), version);
        assertEquals(arn + "/" + version, r.getAttributes().get("ProfileVersionArn"));
    }

    @Test
    void unnamedProfileGetsTheLogicalIdAndARandomSuffix() {
        StackResource r = profile();

        provisioner.provision(r, props(null), ctx(null));

        String name = r.getAttributes().get("ProfileName");
        assertTrue(name.matches("Profile_[A-Za-z0-9]{12}"), name);
        assertEquals(PROFILE_ARN_PREFIX + name, r.getPhysicalId());
    }

    @Test
    void generatedNameStaysWithinTheProfileNameLimit() {
        StackResource r = profile();
        r.setLogicalId("L".repeat(80));

        provisioner.provision(r, props(null), ctx(null));

        String name = r.getAttributes().get("ProfileName");
        assertEquals(64, name.length());
        assertTrue(name.matches("L{51}_[A-Za-z0-9]{12}"), name);
    }

    @Test
    void updateWithTheSameCreateOnlyPropertiesKeepsTheProfile() {
        StackResource r = profile();
        provisioner.provision(r, props(null), ctx(null));
        Map<String, String> created = new HashMap<>(r.getAttributes());
        String physicalId = r.getPhysicalId();

        ObjectNode tagged = props(null);
        tagged.putArray("Tags").addObject().put("Key", "team").put("Value", "a");
        provisioner.provision(r, tagged, ctx(physicalId));

        assertEquals(physicalId, r.getPhysicalId());
        assertEquals(created, r.getAttributes());
    }

    @Test
    void changedPlatformReplacesAnUnnamedProfile() {
        StackResource r = profile();
        provisioner.provision(r, props(null), ctx(null));
        String physicalId = r.getPhysicalId();

        provisioner.provision(r, props(null).put("PlatformId", "Notation-OCI-SHA384-ECDSA"), ctx(physicalId));

        assertNotEquals(physicalId, r.getPhysicalId());
        assertTrue(r.getAttributes().get("ProfileName").matches("Profile_[A-Za-z0-9]{12}"));
    }

    @Test
    void changedValidityPeriodReplacesAnUnnamedProfile() {
        StackResource r = profile();
        provisioner.provision(r, props(null), ctx(null));
        String physicalId = r.getPhysicalId();

        ObjectNode changed = props(null);
        changed.putObject("SignatureValidityPeriod").put("Type", "DAYS").put("Value", 30);
        provisioner.provision(r, changed, ctx(physicalId));

        assertNotEquals(physicalId, r.getPhysicalId());
    }

    @Test
    void reorderedValidityPeriodKeysKeepTheProfile() {
        StackResource r = profile();
        ObjectNode created = props(null);
        created.putObject("SignatureValidityPeriod").put("Type", "DAYS").put("Value", 30);
        provisioner.provision(r, created, ctx(null));
        String physicalId = r.getPhysicalId();

        ObjectNode reordered = props(null);
        reordered.putObject("SignatureValidityPeriod").put("Value", 30).put("Type", "DAYS");
        reordered.putArray("Tags").addObject().put("Key", "team").put("Value", "a");
        provisioner.provision(r, reordered, ctx(physicalId));

        assertEquals(physicalId, r.getPhysicalId());
    }

    @Test
    void rollbackOfAReplacementRestoresThePriorProfile() {
        StackResource r = profile();
        provisioner.provision(r, props(null), ctx(null));
        Map<String, String> created = new HashMap<>(r.getAttributes());
        String physicalId = r.getPhysicalId();
        provisioner.provision(r, props(null).put("PlatformId", "Notation-OCI-SHA384-ECDSA"), ctx(physicalId));

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals(physicalId, r.getPhysicalId());
        assertEquals(created, r.getAttributes());
    }

    @Test
    void rollbackOfAnInPlaceUpdateKeepsTheProfile() {
        StackResource r = profile();
        provisioner.provision(r, props(null), ctx(null));
        String physicalId = r.getPhysicalId();
        ObjectNode tagged = props(null);
        tagged.putArray("Tags").addObject().put("Key", "team").put("Value", "a");
        provisioner.provision(r, tagged, ctx(physicalId));

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals(physicalId, r.getPhysicalId());
    }

    @Test
    void committedReplacementCleansUpTheDisplacedProfile() {
        StackResource r = profile();
        provisioner.provision(r, props(null), ctx(null));
        String physicalId = r.getPhysicalId();
        provisioner.provision(r, props(null).put("PlatformId", "Notation-OCI-SHA384-ECDSA"), ctx(physicalId));

        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(physicalId, provisioner.updateCleanupPhysicalId(r));
        assertTrue(provisioner.completeUpdate(r).complete());
        provisioner.clearUpdate(r);

        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void replacingACustomNamedProfileIsRefused() {
        StackResource r = profile();
        provisioner.provision(r, props("my_profile"), ctx(null));
        ObjectNode changed = props("my_profile").put("PlatformId", "Notation-OCI-SHA384-ECDSA");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, changed, ctx(r.getPhysicalId())));

        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void missingPlatformIdIsRejected() {
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(profile(), mapper.createObjectNode(), ctx(null)));

        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void unknownPlatformIdIsRejected() {
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(profile(), props(null).put("PlatformId", "AWSIoT"), ctx(null)));

        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void validityPeriodWithoutAKnownTypeAndAValueIsRejectedAsSignerRejectsIt() throws Exception {
        String noType = "Signature validity period type must be one of [DAYS, MONTHS, YEARS]";
        Map<String, String> cases = Map.of(
                "{\"Type\": \"WEEKS\", \"Value\": 30}", noType,
                "{}", noType,
                "{\"Value\": 999999}", noType,
                "{\"Type\": \"DAYS\"}", "Signature validity period value must be greater than 0.");
        for (Map.Entry<String, String> period : cases.entrySet()) {
            ObjectNode props = props(null);
            props.set("SignatureValidityPeriod", mapper.readTree(period.getKey()));

            AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(profile(), props, ctx(null)),
                    period.getKey());

            assertEquals("ValidationError", e.getErrorCode(), period.getKey());
            assertEquals(period.getValue(), e.getMessage(), period.getKey());
        }
    }

    @Test
    void validityPeriodOutsideTheSignerLimitsIsRejected() {
        for (String period : List.of("DAYS:0", "DAYS:4106", "MONTHS:136", "YEARS:12")) {
            ObjectNode props = props(null);
            props.putObject("SignatureValidityPeriod").put("Type", period.split(":")[0])
                    .put("Value", Integer.parseInt(period.split(":")[1]));

            AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(profile(), props, ctx(null)),
                    period);

            assertEquals("ValidationError", e.getErrorCode(), period);
        }
    }

    @Test
    void validityPeriodValueThatIsNotAnIntegerIsRejected() {
        for (JsonNode value : List.<JsonNode>of(DoubleNode.valueOf(11.5), BooleanNode.TRUE,
                LongNode.valueOf(99_999_999_999L))) {
            ObjectNode props = props(null);
            props.putObject("SignatureValidityPeriod").put("Type", "YEARS").set("Value", value);

            AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(profile(), props, ctx(null)),
                    value.toString());

            assertEquals("ValidationError", e.getErrorCode(), value.toString());
        }
    }

    @Test
    void validityPeriodAtTheSignerLimitIsAccepted() {
        ObjectNode props = props(null);
        props.putObject("SignatureValidityPeriod").put("Type", "YEARS").put("Value", 11);

        provisioner.provision(profile(), props, ctx(null));
    }

    @Test
    void profileNameOutsideThePatternIsRejected() {
        for (String name : List.of("thiink-signing-profile", "", " ")) {
            AwsException e = assertThrows(AwsException.class,
                    () -> provisioner.provision(profile(), props(name), ctx(null)), "'" + name + "'");

            assertEquals("ValidationError", e.getErrorCode(), "'" + name + "'");
        }
    }
}
