package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/** Provisions {@code AWS::SSM::Parameter}. */
@ApplicationScoped
public class SsmCfnProvisioner implements CfnResourceProvisioner {

    private static final int PARAMETER_NAME_MAX_LENGTH = 2048;

    private final SsmService ssmService;

    public SsmCfnProvisioner(SsmService ssmService) {
        this.ssmService = ssmService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SSM::Parameter");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"),
                r.getLogicalId(), PARAMETER_NAME_MAX_LENGTH, false);
        String value = ctx.resolveOptional(props, "Value");
        if (value == null) {
            value = "";
        }
        String type = ctx.resolveOptional(props, "Type");
        if (type == null) {
            type = "String";
        }
        ssmService.putParameter(name, value, type, null, true, ctx.region());
        r.setPhysicalId(name);
        r.getAttributes().put("Name", name);
        r.getAttributes().put("Type", type);
        r.getAttributes().put("Value", value);
        r.getAttributes().put("Arn", parameterArn(name, ctx));
    }

    /** AWS's form is {@code parameter/<name>} whether or not the name starts with a slash. */
    private static String parameterArn(String name, ProvisionContext ctx) {
        String path = name.startsWith("/") ? name : "/" + name;
        return AwsArnUtils.Arn.of("ssm", ctx.region(), ctx.accountId(), "parameter" + path).toString();
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ssmService.deleteParameter(physicalId, region);
    }
}
