package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
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
 * replacing any custom-named resource. A replaced or deleted profile leaves nothing behind to clean
 * up, and a rolled-back replacement points the resource back to the prior profile.
 */
@ApplicationScoped
public class SignerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Signer::SigningProfile";
    private static final List<String> PLATFORMS = List.of("AWSLambda-SHA384-ECDSA", "Notation-OCI-SHA384-ECDSA");
    private static final Pattern PROFILE_NAME = Pattern.compile("[0-9a-zA-Z_]{2,64}");
    private static final Map<String, Integer> MAX_VALIDITY = Map.of("DAYS", 4105, "MONTHS", 135, "YEARS", 11);
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** The create-only properties the profile was minted from, so an update can tell whether it is replaced. */
    private static final String CREATE_ONLY_ATTR = "FlociSigningProfileCreateOnly";

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String platformId = ctx.resolveOptional(props, "PlatformId");
        if (platformId == null || !PLATFORMS.contains(platformId)) {
            throw new AwsException("ValidationError",
                    TYPE + " PlatformId must be one of " + PLATFORMS + ", got " + platformId, 400);
        }
        String explicitName = ctx.resolveOptional(props, "ProfileName");
        if (explicitName != null && !PROFILE_NAME.matcher(explicitName).matches()) {
            throw new AwsException("ValidationError",
                    TYPE + " ProfileName must be 2 to 64 characters from [0-9a-zA-Z_], got " + explicitName, 400);
        }
        JsonNode period = ctx.engine().resolveNodeOmittingNoValue(props.path("SignatureValidityPeriod"));
        validatePeriod(period, ctx);
        String createOnly = platformId + "|" + Objects.toString(explicitName, "") + "|"
                + period.path("Type").asText() + "|" + period.path("Value").asText();
        if (!createOnly.equals(r.getAttributes().get(CREATE_ONLY_ATTR))) {
            if (ctx.isUpdate() && explicitName != null && explicitName.equals(r.getAttributes().get("ProfileName"))) {
                throw new AwsException("ValidationError",
                        "CloudFormation cannot update a stack when a custom-named resource requires "
                                + "replacing. Rename " + explicitName + " and update the stack again.", 400);
            }
            mint(r, ctx, explicitName, createOnly);
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Signer's checks on a supplied validity period, in its order and with its messages: a known unit,
     * then a value above zero and at most the unit's cap. An omitted period keeps Signer's default.
     */
    private static void validatePeriod(JsonNode period, ProvisionContext ctx) {
        if (period.isMissingNode()) {
            return;
        }
        String type = period.path("Type").asText(null);
        if (type == null || !MAX_VALIDITY.containsKey(type)) {
            throw new AwsException("ValidationError",
                    "Signature validity period type must be one of [DAYS, MONTHS, YEARS]", 400);
        }
        Integer value = ctx.resolveOptionalInt(period, "Value");
        if (value == null || value < 1) {
            throw new AwsException("ValidationError", "Signature validity period value must be greater than 0.", 400);
        }
        if (value > MAX_VALIDITY.get(type)) {
            throw new AwsException("ValidationError", "Signature validity period value for " + type
                    + " must be less than or equal to " + MAX_VALIDITY.get(type), 400);
        }
    }

    private static void mint(StackResource r, ProvisionContext ctx, String explicitName, String createOnly) {
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

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    /** A replacement points back to the prior profile; a tags-only update changed nothing Floci holds. */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ReplacementCleanup.rollback(resource, this::delete);
        return true;
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
