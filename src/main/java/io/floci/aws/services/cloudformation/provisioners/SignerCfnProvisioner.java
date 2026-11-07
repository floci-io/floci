package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.signer.SignerPlatforms;
import io.floci.aws.services.signer.SignerService;
import io.floci.aws.services.signer.model.SigningProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Provisions {@code AWS::Signer::SigningProfile} through {@link SignerService}, so a profile a stack
 * creates is the profile the Signer API serves: {@code aws signer get-signing-profile} returns the
 * same ARN the stack hands out as {@code Ref}, with its platform, validity period and tags.
 *
 * <p>{@code Ref} is the profile ARN and the attributes carry the name, a version and the version ARN
 * a code signing config lists. Every property but {@code Tags} is create-only: an update that keeps
 * them keeps the profile and re-applies its tags, one that changes them mints a new profile, and one
 * that would keep an explicit name is refused, as CloudFormation refuses replacing any custom-named
 * resource. A replaced profile is cancelled once the update commits; a rolled-back replacement puts
 * the resource back and cancels the profile that update created.
 */
@ApplicationScoped
public class SignerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Signer::SigningProfile";
    private static final List<String> PLATFORMS = List.of("AWSLambda-SHA384-ECDSA", "Notation-OCI-SHA384-ECDSA");
    private static final Pattern PROFILE_NAME = Pattern.compile("[0-9a-zA-Z_]{2,64}");
    private static final Map<String, Integer> MAX_VALIDITY = Map.of("DAYS", 4105, "MONTHS", 135, "YEARS", 11);
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String PROFILE_RESOURCE = "/signing-profiles/";
    /** The create-only properties the profile was minted from, so an update can tell whether it is replaced. */
    private static final String CREATE_ONLY_ATTR = "FlociSigningProfileCreateOnly";
    /**
     * The tags an in-place update is about to replace, with the region they live in, so a rollback
     * can put them back. Present only while a tag change is pending: a provision that would write
     * the set already stored removes it.
     */
    private static final String PRIOR_TAGS_ATTR = "FlociSigningProfilePriorTags";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SignerService signerService;

    @Inject
    public SignerCfnProvisioner(SignerService signerService) {
        this.signerService = signerService;
    }

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
        Integer validityValue = validatePeriod(period, ctx);
        String createOnly = platformId + "|" + Objects.toString(explicitName, "") + "|"
                + period.path("Type").asText() + "|" + period.path("Value").asText();
        if (!createOnly.equals(r.getAttributes().get(CREATE_ONLY_ATTR))) {
            if (ctx.isUpdate() && explicitName != null && explicitName.equals(r.getAttributes().get("ProfileName"))) {
                throw new AwsException("ValidationError",
                        "CloudFormation cannot update a stack when a custom-named resource requires "
                                + "replacing. Rename " + explicitName + " and update the stack again.", 400);
            }
            mint(r, ctx, platformId, explicitName, period, validityValue, createOnly, tags(ctx, props));
        } else {
            replaceTags(r, ctx, tags(ctx, props));
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Signer's checks on a supplied validity period, in its order and with its messages: a known unit,
     * then a value above zero and at most the unit's cap. An omitted period keeps Signer's default.
     */
    private static Integer validatePeriod(JsonNode period, ProvisionContext ctx) {
        if (period.isMissingNode()) {
            return null;
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
        return value;
    }

    private void mint(StackResource r, ProvisionContext ctx, String platformId, String explicitName,
                      JsonNode period, Integer validityValue, String createOnly, Map<String, String> tags) {
        String name = explicitName != null ? explicitName
                : r.getLogicalId().substring(0, Math.min(r.getLogicalId().length(), 51)) + "_" + random(12);
        if (explicitName != null && signerService.findSigningProfile(explicitName, ctx.region()).isPresent()) {
            throw new AwsException("ValidationError", TYPE + " " + explicitName
                    + " already exists in " + ctx.region()
                    + "; a stack cannot take over a signing profile it did not create.", 400);
        }
        SigningProfile profile = signerService.putSigningProfile(
                name,
                platformId,
                period.isMissingNode() ? null : period.path("Type").asText(null),
                validityValue,
                tags,
                ctx.region());
        r.setPhysicalId(profile.getArn());
        r.getAttributes().put("Arn", profile.getArn());
        r.getAttributes().put("ProfileName", profile.getProfileName());
        r.getAttributes().put("ProfileVersion", profile.getProfileVersion());
        r.getAttributes().put("ProfileVersionArn", profile.getProfileVersionArn());
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnly);
    }

    /**
     * A tags-only update. CloudFormation sets the resource's tags to exactly what the template
     * declares, so this replaces the set rather than merging into it, and it does not mint a profile
     * version: on AWS, tagging a signing profile is not a change to the profile.
     */
    private void replaceTags(StackResource r, ProvisionContext ctx, Map<String, String> tags) {
        String name = r.getAttributes().get("ProfileName");
        if (name == null) {
            return;
        }
        Optional<SigningProfile> stored = signerService.findSigningProfile(name, ctx.region());
        if (stored.isEmpty()) {
            adopt(r, ctx, name);
            signerService.replaceTags(name, tags, ctx.region());
            return;
        }
        if (stored.get().getTags().equals(tags)) {
            r.getAttributes().remove(PRIOR_TAGS_ATTR);
            return;
        }
        snapshotTags(r, ctx, stored.get().getTags());
        signerService.replaceTags(name, tags, ctx.region());
    }

    /**
     * Writes the service record for a resource this stack provisioned before the service existed,
     * keeping the identifiers the stack already handed out. The create-only property string the
     * provisioner recorded carries the platform and the validity period, which is all a later tag
     * update needs.
     */
    private void adopt(StackResource r, ProvisionContext ctx, String name) {
        String createOnly = r.getAttributes().get(CREATE_ONLY_ATTR);
        String[] parts = createOnly == null ? new String[0] : createOnly.split("\\|", -1);
        String platformId = parts.length > 0 ? parts[0] : null;
        if (platformId == null || SignerPlatforms.find(platformId).isEmpty()) {
            return;
        }
        String validityType = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        Integer validityValue = null;
        if (validityType != null && parts.length > 3) {
            try {
                validityValue = Integer.valueOf(parts[3]);
            } catch (NumberFormatException ignored) {
                validityValue = null;
            }
        }
        signerService.adoptSigningProfile(name, platformId, r.getAttributes().get("ProfileVersion"),
                validityType, validityValue, ctx.region());
    }

    private static void snapshotTags(StackResource r, ProvisionContext ctx, Map<String, String> tags) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", ctx.region());
        ObjectNode stored = snapshot.putObject("tags");
        tags.forEach(stored::put);
        try {
            r.getAttributes().put(PRIOR_TAGS_ATTR, MAPPER.writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationError", "Could not record the profile's prior tags: "
                    + e.getMessage(), 400);
        }
    }

    private void restorePriorTags(StackResource r, String snapshotJson) {
        try {
            JsonNode snapshot = MAPPER.readTree(snapshotJson);
            String region = snapshot.path("region").asText(null);
            String name = r.getAttributes().get("ProfileName");
            if (region == null || name == null) {
                return;
            }
            Map<String, String> tags = new LinkedHashMap<>();
            snapshot.path("tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
            signerService.replaceTags(name, tags, region);
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationError", "Could not restore the profile's prior tags: "
                    + e.getMessage(), 400);
        }
    }

    /**
     * The template's {@code Tags}, with every key and value resolved: a {@code Ref}, an
     * {@code Fn::Sub} or a {@code Fn::If} inside a tag is a reference to something the engine owns,
     * and reading the raw node would store the intrinsic document as the tag.
     */
    private static Map<String, String> tags(ProvisionContext ctx, JsonNode props) {
        JsonNode tags = ctx.engine().resolveNodeOmittingNoValue(props.path("Tags"));
        if (tags.isMissingNode() || tags.isNull() || !tags.isArray()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (JsonNode tag : tags) {
            String key = ctx.engine().resolveNodeOmittingNoValue(tag.path("Key")).asText(null);
            if (key == null || key.isEmpty()) {
                continue;
            }
            resolved.put(key, ctx.engine().resolveNodeOmittingNoValue(tag.path("Value")).asText(""));
        }
        return resolved;
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        String name = profileNameOf(physicalId);
        if (name == null) {
            return;
        }
        CfnDeletes.safeDelete("Signing profile", physicalId,
                () -> signerService.cancelSigningProfile(name, region),
                "ResourceNotFoundException");
    }

    /** The profile name the ARN names, so a delete driven by a physical id alone can cancel it. */
    private static String profileNameOf(String physicalId) {
        if (physicalId == null) {
            return null;
        }
        int at = physicalId.indexOf(PROFILE_RESOURCE);
        if (at < 0) {
            return null;
        }
        String remainder = physicalId.substring(at + PROFILE_RESOURCE.length());
        int slash = remainder.indexOf('/');
        return slash < 0 ? remainder : remainder.substring(0, slash);
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
        resource.getAttributes().remove(PRIOR_TAGS_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    /** A replacement points back to the prior profile and cancels the one this update created. */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String pendingTags = resource.getAttributes().get(PRIOR_TAGS_ATTR);
        if (!ReplacementCleanup.hasReplacement(resource) && pendingTags != null) {
            restorePriorTags(resource, pendingTags);
            resource.getAttributes().remove(PRIOR_TAGS_ATTR);
            return true;
        }
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
