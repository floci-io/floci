package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.UUID;

/**
 * Provisions {@code AWS::CDK::Metadata}, the analytics marker the CDK toolchain adds to synthesized
 * templates. It backs no service, so provisioning is just a physical id: without one the stack
 * would still succeed via the stub path, but with a fake ARN attribute the real type never has.
 */
@ApplicationScoped
public class CdkMetadataCfnProvisioner implements CfnResourceProvisioner {

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CDK::Metadata");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.setPhysicalId("cdk-metadata-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
