package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contexts for provisioner unit tests that need no real intrinsic resolution: a mocked engine that
 * hands scalars back as text and nodes back unchanged, and an update context carrying the previous
 * template's properties, as the stack hands them over.
 */
final class ProvisionContexts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProvisionContexts() {
    }

    /**
     * Scalar properties only: intrinsic resolution (Ref/GetAtt) is the engine's job and is covered by
     * its own tests, so a passthrough keeps a provisioner test a true isolated unit test.
     */
    static CloudFormationTemplateEngine passthroughEngine() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return engine;
    }

    /** An update: the prior physical id and the previous template's properties, as the stack hands them over. */
    static ProvisionContext update(String region, String accountId, String stackName,
                                   String priorPhysicalId, String priorProperties) throws Exception {
        return new ProvisionContext(passthroughEngine(), region, accountId, stackName, priorPhysicalId,
                event -> {}, MAPPER.readTree(priorProperties));
    }
}
