package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Provisions {@code AWS::Signer::SigningProfile}. Floci has no Signer API, so a profile is only the
 * identifiers CloudFormation hands the template: {@code Ref} is the profile ARN, and the attributes
 * carry the name, a version and the version ARN a code signing config lists. Every property but
 * {@code Tags} is create-only: an update that keeps them keeps the profile, one that changes them
 * mints a new one, and one that would keep an explicit name is refused, as CloudFormation refuses
 * replacing any custom-named resource. A deleted stack leaves nothing behind to clean up.
 */
@ApplicationScoped
public class SignerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Signer::SigningProfile";
    private static final List<String> PLATFORMS = List.of("AWSLambda-SHA384-ECDSA", "Notation-OCI-SHA384-ECDSA");
    private static final Pattern PROFILE_NAME = Pattern.compile("[0-9a-zA-Z_]{2,64}");
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** The create-only properties the profile was minted from, so an update can tell whether it is replaced. */
    private static final String CREATE_ONLY_ATTR = "FlociSigningProfileCreateOnly";

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String platformId = ctx.resolveOptional(props, "PlatformId");
        if (platformId == null || !PLATFORMS.contains(platformId)) {
            throw new AwsException("ValidationError",
                    TYPE + " PlatformId must be one of " + PLATFORMS + ", got " + platformId, 400);
        }
        String explicitName = ctx.resolveOrDefault(props, "ProfileName", null);
        if (explicitName != null && !PROFILE_NAME.matcher(explicitName).matches()) {
            throw new AwsException("ValidationError",
                    TYPE + " ProfileName must be 2 to 64 characters from [0-9a-zA-Z_], got " + explicitName, 400);
        }
        String createOnly = platformId + "|" + Objects.toString(explicitName, "") + "|"
                + ctx.engine().resolveNode(props.path("SignatureValidityPeriod"));
        if (ctx.isUpdate()) {
            if (createOnly.equals(r.getAttributes().get(CREATE_ONLY_ATTR))) {
                return;
            }
            if (explicitName != null && explicitName.equals(r.getAttributes().get("ProfileName"))) {
                throw new AwsException("ValidationError",
                        "CloudFormation cannot update a stack when a custom-named resource requires "
                                + "replacing. Rename " + explicitName + " and update the stack again.", 400);
            }
        }
        String name = explicitName != null ? explicitName
                : r.getLogicalId().substring(0, Math.min(r.getLogicalId().length(), 51)) + "_" + random(12);
        String arn = AwsArnUtils.Arn.of("signer", ctx.region(), ctx.accountId(), "/signing-profiles/" + name)
                .toString();
        String version = random(10);
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("ProfileName", name);
        r.getAttributes().put("ProfileVersion", version);
        r.getAttributes().put("ProfileVersionArn", arn + "/" + version);
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnly);
    }

    private static String random(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return out.toString();
    }
}
