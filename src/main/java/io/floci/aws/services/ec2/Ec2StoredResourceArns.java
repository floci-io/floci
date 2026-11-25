package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves EC2 names and attachments and reads the tags of the resource an ARN names. */
@ApplicationScoped
public class Ec2StoredResourceArns implements StoredResourceArns {

    private final Ec2Service ec2;
    private final RegionResolver regionResolver;

    @Inject
    public Ec2StoredResourceArns(Ec2Service ec2, RegionResolver regionResolver) {
        this.ec2 = ec2;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return "ec2";
    }

    @Override
    public Map<String, String> storedTags(String resource) {
        if (!AwsArnUtils.isArn(resource)) {
            return Map.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        String[] parts = arn.resource().split("/", 2);
        if (!"ec2".equals(arn.service()) || parts.length != 2 || parts[1].isEmpty()
                || resource.contains("*") || resource.contains("?")) {
            return Map.of();
        }
        String account = arn.accountId().isEmpty() ? regionResolver.getAccountId() : arn.accountId();
        Map<String, String> tags = new LinkedHashMap<>();
        for (Tag tag : ec2.resourceTags(account, arn.region(), parts[1])) {
            tags.put(tag.getKey(), tag.getValue() == null ? "" : tag.getValue());
        }
        return tags;
    }

    @Override
    public Optional<String> findByMembers(String type, List<String> names, String region) {
        if (("instance".equals(type) || "network-interface".equals(type)) && names.size() == 1) {
            return ec2.networkInterfaceAttachmentArns(region, names.getFirst()).stream()
                    .filter(arn -> AwsArnUtils.parse(arn).resource().startsWith(type + "/"))
                    .findFirst();
        }
        return find(type, names, region);
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return "launch-template".equals(type) && names.size() == 1
                ? ec2.findLaunchTemplateArn(region, names.getFirst()) : Optional.empty();
    }
}
