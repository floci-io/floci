package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.ec2.model.LaunchTemplateData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code MetadataOptions} block that {@code AWS::EC2::Instance} and
 * {@code AWS::EC2::LaunchTemplate}'s {@code LaunchTemplateData} share. A launch template stores it
 * unchecked, as EC2 does; the instance provisioner checks the options it is about to apply with
 * EC2's own rules before it launches or changes anything.
 */
final class Ec2CfnMetadataOptions {

    private static final String HTTP_TOKENS = "HttpTokens";
    private static final String HOP_LIMIT = "HttpPutResponseHopLimit";
    private static final String HTTP_ENDPOINT = "HttpEndpoint";
    private static final String HTTP_PROTOCOL_IPV6 = "HttpProtocolIpv6";
    private static final String INSTANCE_METADATA_TAGS = "InstanceMetadataTags";

    /** The shape AWS::EC2::Instance's resource schema declares for MetadataOptions. */
    static final String INSTANCE_SHAPE = CfnPropertyTypes.propertyShape("AWS::EC2::Instance", "MetadataOptions");
    /** The shape AWS::EC2::LaunchTemplate's resource schema declares for LaunchTemplateData's MetadataOptions. */
    static final String LAUNCH_TEMPLATE_SHAPE = CfnPropertyTypes.memberShape(
            CfnPropertyTypes.propertyShape("AWS::EC2::LaunchTemplate", "LaunchTemplateData"), "MetadataOptions");

    private Ec2CfnMetadataOptions() {}

    /**
     * The options the block {@code raw}, at {@code path} in the resource's properties, names, with
     * absent members left null, or null when there is no block. A member's text is kept as written,
     * so an empty or padded value reaches EC2's checks and is refused, as EC2 refuses it, rather
     * than reading as absent. {@code AWS::NoValue} is what leaves a member out. It is read against the
     * schema shape {@code shape}, as {@code AWS::AutoScaling::LaunchConfiguration}'s is: a block or
     * member of the wrong type, such as {@code HttpTokens: [required]}, fails the resource rather
     * than reading as absent, which would leave tokens optional. A null member takes the launch
     * default when an instance launches; on an update, {@link #toApply} decides it.
     */
    static LaunchTemplateData.MetadataOptions read(String path, JsonNode raw, String shape,
                                                   CloudFormationTemplateEngine engine) {
        CfnPropertyTypes.Reader types = new CfnPropertyTypes.Reader(engine);
        JsonNode block = types.read(path, raw, shape);
        types.finish();
        if (block == null || block.isEmpty()) {
            return null;
        }
        LaunchTemplateData.MetadataOptions options = new LaunchTemplateData.MetadataOptions();
        options.setHttpTokens(member(block, HTTP_TOKENS));
        options.setHttpEndpoint(member(block, HTTP_ENDPOINT));
        options.setHttpProtocolIpv6(member(block, HTTP_PROTOCOL_IPV6));
        options.setInstanceMetadataTags(member(block, INSTANCE_METADATA_TAGS));
        JsonNode hopLimit = block.path(HOP_LIMIT);
        if (hopLimit.isInt()) {
            options.setHttpPutResponseHopLimit(hopLimit.intValue());
        }
        return options;
    }

    /** {@code declared} over {@code base}, member by member; either may be null. */
    static LaunchTemplateData.MetadataOptions over(LaunchTemplateData.MetadataOptions base,
                                                   LaunchTemplateData.MetadataOptions declared) {
        if (base == null) {
            return declared;
        }
        return LaunchTemplateData.MetadataOptions.merge(base, declared);
    }

    /**
     * What an update applies to the instance it keeps: every option the template declares now, and
     * the launch default for any member it declared last time but no longer does, so turning an
     * option off in the template turns it off on the instance. Members it never declared are left
     * as the instance has them. Null when nothing was declared, then or now.
     */
    static LaunchTemplateData.MetadataOptions toApply(Set<String> previouslyDeclared,
                                                      LaunchTemplateData.MetadataOptions declared) {
        if (previouslyDeclared.isEmpty()) {
            return declared;
        }
        LaunchTemplateData.MetadataOptions defaults = LaunchTemplateData.MetadataOptions.launchDefaults();
        LaunchTemplateData.MetadataOptions reset = new LaunchTemplateData.MetadataOptions();
        if (previouslyDeclared.contains(HTTP_TOKENS)) {
            reset.setHttpTokens(defaults.getHttpTokens());
        }
        if (previouslyDeclared.contains(HOP_LIMIT)) {
            reset.setHttpPutResponseHopLimit(defaults.getHttpPutResponseHopLimit());
        }
        if (previouslyDeclared.contains(HTTP_ENDPOINT)) {
            reset.setHttpEndpoint(defaults.getHttpEndpoint());
        }
        if (previouslyDeclared.contains(HTTP_PROTOCOL_IPV6)) {
            reset.setHttpProtocolIpv6(defaults.getHttpProtocolIpv6());
        }
        if (previouslyDeclared.contains(INSTANCE_METADATA_TAGS)) {
            reset.setInstanceMetadataTags(defaults.getInstanceMetadataTags());
        }
        return LaunchTemplateData.MetadataOptions.merge(reset, declared);
    }

    /** Whether applying {@code declared} would change any option {@code current} has. */
    static boolean changes(LaunchTemplateData.MetadataOptions current, LaunchTemplateData.MetadataOptions declared) {
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.merge(current, declared);
        return !Objects.equals(current.getHttpTokens(), applied.getHttpTokens())
                || !Objects.equals(current.getHttpPutResponseHopLimit(), applied.getHttpPutResponseHopLimit())
                || !Objects.equals(current.getHttpEndpoint(), applied.getHttpEndpoint())
                || !Objects.equals(current.getHttpProtocolIpv6(), applied.getHttpProtocolIpv6())
                || !Objects.equals(current.getInstanceMetadataTags(), applied.getInstanceMetadataTags());
    }

    /**
     * The CloudFormation names of the members {@code declared} sets, comma-separated, for a resource
     * attribute, or null when it sets none. Only which members were declared is kept: that is all
     * {@link #toApply} needs, and it does not depend on how the options are stored.
     */
    static String declaredMembers(LaunchTemplateData.MetadataOptions declared) {
        if (declared == null) {
            return null;
        }
        List<String> members = new ArrayList<>();
        if (declared.getHttpTokens() != null) {
            members.add(HTTP_TOKENS);
        }
        if (declared.getHttpPutResponseHopLimit() != null) {
            members.add(HOP_LIMIT);
        }
        if (declared.getHttpEndpoint() != null) {
            members.add(HTTP_ENDPOINT);
        }
        if (declared.getHttpProtocolIpv6() != null) {
            members.add(HTTP_PROTOCOL_IPV6);
        }
        if (declared.getInstanceMetadataTags() != null) {
            members.add(INSTANCE_METADATA_TAGS);
        }
        return members.isEmpty() ? null : String.join(",", members);
    }

    /** The member names {@link #declaredMembers} recorded; empty when there is no record. */
    static Set<String> parseDeclaredMembers(String recorded) {
        if (recorded == null || recorded.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(recorded.split(",")).map(String::trim).filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String member(JsonNode block, String name) {
        JsonNode value = block.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
