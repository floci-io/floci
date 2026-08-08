package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPCEndpoint}.
 *
 * <p>Updates are handled as replacement: a new endpoint is created and the previous one is
 * deleted, so re-deploys do not accumulate orphaned endpoints. {@code Ec2Service} has no
 * modify operation, so mutable-property updates also replace; this matches how the other
 * EC2 networking types behave here while still cleaning up the prior endpoint.
 */
@ApplicationScoped
public class Ec2VpcEndpointCfnProvisioner implements CfnResourceProvisioner {

    private final Ec2Service ec2Service;

    @Inject
    public Ec2VpcEndpointCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::VPCEndpoint");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String vpcId = ctx.resolveOptional(props, "VpcId");
        String serviceName = ctx.resolveOptional(props, "ServiceName");
        String endpointType = ctx.resolveOptional(props, "VpcEndpointType");
        // Resolve through the engine so Refs/parameters work, not just literal booleans. When the
        // property is absent, pass null and let Ec2Service apply its own default (true for
        // Interface endpoints, false otherwise), matching the AWS default.
        String privateDns = ctx.resolveOptional(props, "PrivateDnsEnabled");
        Boolean privateDnsEnabled = privateDns != null && !privateDns.isBlank()
                ? Boolean.parseBoolean(privateDns)
                : null;
        String previousEndpointId = r.getPhysicalId();
        var endpoint = ec2Service.createVpcEndpoint(ctx.region(), vpcId, serviceName,
                endpointType != null ? endpointType : "Gateway",
                resolveIdList(props, "RouteTableIds", ctx),
                resolveIdList(props, "SubnetIds", ctx),
                resolveIdList(props, "SecurityGroupIds", ctx),
                privateDnsEnabled,
                List.of());
        r.setPhysicalId(endpoint.getVpcEndpointId());
        r.getAttributes().put("Id", endpoint.getVpcEndpointId());
        if (previousEndpointId != null && !previousEndpointId.equals(endpoint.getVpcEndpointId())) {
            deleteReplacedEndpoint(previousEndpointId, ctx.region());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ec2Service.deleteVpcEndpoints(region, List.of(physicalId));
    }

    /** Resolve an array property of Ref/GetAtt entries into plain id strings. */
    private List<String> resolveIdList(JsonNode props, String field, ProvisionContext ctx) {
        List<String> ids = new ArrayList<>();
        if (props != null && props.has(field) && props.get(field).isArray()) {
            for (JsonNode entry : props.get(field)) {
                String id = ctx.engine().resolve(entry);
                if (id != null && !id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    private void deleteReplacedEndpoint(String endpointId, String region) {
        try {
            ec2Service.deleteVpcEndpoints(region, List.of(endpointId));
        } catch (AwsException e) {
            if (!"InvalidVpcEndpointId.NotFound".equals(e.getErrorCode()) && e.getHttpStatus() != 404) {
                throw e;
            }
        }
    }
}
