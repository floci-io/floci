package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    /** Same rendering DescribeVpcEndpoints uses, so the attribute reads as the API reports it. */
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

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
                policyDocument(props, ctx),
                List.of());
        r.setPhysicalId(endpoint.getVpcEndpointId());
        r.getAttributes().put("Id", endpoint.getVpcEndpointId());
        // An unset attribute resolves to the literal "LogicalId.CreationTimestamp" rather than
        // failing, so leaving it out hands the template a wrong value quietly.
        if (endpoint.getCreationTimestamp() != null) {
            r.getAttributes().put("CreationTimestamp", ISO_FMT.format(endpoint.getCreationTimestamp()));
        }
        if (previousEndpointId != null && !previousEndpointId.equals(endpoint.getVpcEndpointId())) {
            deleteReplacedEndpoint(previousEndpointId, ctx.region());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ec2Service.deleteVpcEndpoints(region, List.of(physicalId));
    }

    /**
     * {@code PolicyDocument} is declared as JSON in the template, so it arrives as an object node
     * whose fields may still carry intrinsics ({@code Ref}, {@code Fn::Sub}, {@code Fn::Join}).
     * Serialising the raw node would store literal template syntax and DescribeVpcEndpoints would
     * report it verbatim, so it goes through
     * {@link io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine#resolveJsonAttribute},
     * the engine helper for JSON-valued attributes (policy documents, RedrivePolicy,
     * FilterPolicy, Step Functions definitions). That helper also avoids the double-encoding
     * trap a naive {@code resolveNode(...).toString()} hits: resolveNode collapses an intrinsic
     * to a TextNode holding already-serialized JSON, which {@code toString()} then re-quotes.
     *
     * <p>It returns null for a null or missing node, so no {@code has}/{@code isNull} guard is
     * needed here beyond the null-props check.
     *
     * @see <a href="https://github.com/floci-io/floci/issues/2317">#2317</a>
     */
    private String policyDocument(JsonNode props, ProvisionContext ctx) {
        if (props == null) {
            return null;
        }
        return ctx.engine().resolveJsonAttribute(props.path("PolicyDocument"));
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
