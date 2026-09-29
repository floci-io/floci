package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Provisions {@code AWS::SSM::Parameter}. */
@ApplicationScoped
public class SsmCfnProvisioner implements CfnResourceProvisioner {

    private static final int PARAMETER_NAME_MAX_LENGTH = 2048;
    private static final String SSM_TEMPLATE_TAG_KEYS_ATTR = "FlociSsmTemplateTagKeys";

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
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        SsmService.validateTagKeys(tags);
        ssmService.putParameter(name, value, type, null, true, ctx.region());
        reconcileTags(name, r.getAttributes().get(SSM_TEMPLATE_TAG_KEYS_ATTR), tags, ctx.region());
        r.setPhysicalId(name);
        r.getAttributes().put("Name", name);
        r.getAttributes().put("Type", type);
        r.getAttributes().put("Value", value);
        r.getAttributes().put("Arn", parameterArn(name, ctx));
        // Tag keys cannot contain a comma, so the sorted keys join losslessly.
        if (tags.isEmpty()) {
            r.getAttributes().remove(SSM_TEMPLATE_TAG_KEYS_ATTR);
        } else {
            r.getAttributes().put(SSM_TEMPLATE_TAG_KEYS_ATTR, String.join(",", new TreeSet<>(tags.keySet())));
        }
    }

    /**
     * Applies the template's tags and removes only the keys the previous template set and this one
     * drops, so a tag added outside the template stays, as in AWS. The overwriting put keeps the
     * parameter's existing tags, and a parameter provisioned before the keys were recorded has none
     * to drop.
     */
    private void reconcileTags(String name, String previousTemplateKeys, Map<String, String> desired,
                               String region) {
        List<String> dropped = previousTemplateKeys == null || previousTemplateKeys.isEmpty()
                ? List.of()
                : Arrays.stream(previousTemplateKeys.split(","))
                        .filter(key -> !desired.containsKey(key))
                        .toList();
        if (!dropped.isEmpty()) {
            ssmService.removeTagsFromResource(name, dropped, region);
        }
        if (!desired.isEmpty()) {
            ssmService.addTagsToResource(name, desired, region);
        }
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
