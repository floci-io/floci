package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.Set;

/**
 * Provisions and deletes the CloudFormation resource types for a single service, replacing one
 * arm of the switch in {@code CloudFormationResourceProvisioner}. Implementations inject only
 * the service they wrap and are discovered via CDI by {@link CloudFormationResourceRegistry}.
 *
 * <p>{@code provision} mutates the passed {@link StackResource} in place — setting its physical
 * id and populating its attributes for {@code Fn::GetAtt} — exactly as the original per-type
 * methods did. {@code resource.getResourceType()} disambiguates when a provisioner serves more
 * than one type.
 */
public interface CfnResourceProvisioner {

    Set<String> resourceTypes();

    void provision(StackResource resource, JsonNode properties, ProvisionContext ctx);

    /** Most implementations delegate to {@code service.deleteX(physicalId, region)}. */
    default void delete(String resourceType, String physicalId, String region) {
        // no-op by default: some resource types have no backing delete
    }
}
