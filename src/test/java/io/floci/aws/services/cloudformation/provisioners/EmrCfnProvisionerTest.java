package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emr.EmrService;
import io.floci.aws.services.emr.model.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static io.floci.aws.services.cloudformation.provisioners.ProvisionContexts.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code AWS::EMR::SecurityConfiguration} in isolation, with {@link EmrService} mocked. */
class EmrCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENCRYPTION = """
            {"EncryptionConfiguration": {"EnableInTransitEncryption": false, "EnableAtRestEncryption": false}}""";

    private final EmrService emr = mock(EmrService.class);
    private final EmrCfnProvisioner provisioner = new EmrCfnProvisioner(emr);

    private static ProvisionContext ctx(String priorPhysicalId, String priorProperties) {
        return ProvisionContexts.standalone(priorPhysicalId, priorProperties == null ? null : json(priorProperties));
    }

    private static StackResource resource(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Security");
        r.setResourceType("AWS::EMR::SecurityConfiguration");
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }


    private static String props(String name, String configuration) {
        return "{" + (name == null ? "" : "\"Name\": \"" + name + "\", ") + "\"SecurityConfiguration\": "
                + configuration + "}";
    }

    @Test
    void createsTheSecurityConfigurationFromAnObjectOrItsText() {
        StackResource fromObject = resource(null);
        StackResource fromText = resource(null);

        provisioner.provision(fromObject, json(props("tls", ENCRYPTION)), ctx(null, null));
        provisioner.provision(fromText, json(props("text", MAPPER.valueToTree(ENCRYPTION).toString())),
                ctx(null, null));

        verify(emr).createSecurityConfiguration("tls", json(ENCRYPTION).toString(), "us-east-1");
        verify(emr).createSecurityConfiguration("text", ENCRYPTION, "us-east-1");
        assertEquals("tls", fromObject.getPhysicalId());
        assertEquals("text", fromText.getPhysicalId());
    }

    @Test
    void anUnnamedSecurityConfigurationGetsAGeneratedName() {
        StackResource r = resource(null);

        provisioner.provision(r, json(props(null, ENCRYPTION)), ctx(null, null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(emr).createSecurityConfiguration(name.capture(), anyString(), eq("us-east-1"));
        assertTrue(name.getValue().startsWith("my-stack-Security-"), name.getValue());
        assertEquals(name.getValue(), r.getPhysicalId());
    }

    @Test
    void anUpdateThatChangesNothingKeepsIt() {
        StackResource r = resource("tls");

        provisioner.provision(r, json(props("tls", ENCRYPTION)), ctx("tls", props("tls", ENCRYPTION)));

        assertEquals("tls", r.getPhysicalId());
        verifyNoInteractions(emr);
    }

    @Test
    void aChangeReplacesAnUnnamedOneAndIsRefusedForOneThatKeepsItsName() {
        String changed = ENCRYPTION.replace("\"EnableAtRestEncryption\": false", "\"EnableAtRestEncryption\": true");
        StackResource unnamed = resource("my-stack-Security-OLD");

        provisioner.provision(unnamed, json(props(null, changed)),
                ctx("my-stack-Security-OLD", props(null, ENCRYPTION)));
        AwsException named = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("tls"), json(props("tls", changed)),
                        ctx("tls", props("tls", ENCRYPTION))));

        assertTrue(unnamed.getPhysicalId().startsWith("my-stack-Security-"), unnamed.getPhysicalId());
        assertEquals("my-stack-Security-OLD", provisioner.updateCleanupPhysicalId(unnamed));
        assertTrue(named.getMessage().startsWith("CloudFormation cannot update a stack when a custom-named resource"),
                named.getMessage());
    }

    @Test
    void theDeleteTakesASecurityConfigurationTheRegionDoesNotListAsDeleted() {
        SecurityConfiguration tls = new SecurityConfiguration();
        tls.setName("tls");
        when(emr.listSecurityConfigurations("us-east-1")).thenReturn(List.of(tls));

        provisioner.delete("AWS::EMR::SecurityConfiguration", "tls", "us-east-1");
        provisioner.delete("AWS::EMR::SecurityConfiguration", "gone", "us-east-1");

        verify(emr).deleteSecurityConfiguration("tls", "us-east-1");
        verify(emr, never()).deleteSecurityConfiguration(eq("gone"), any());
    }
}
