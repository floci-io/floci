package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/** Resolves the caller's launch-template names and network-interface attachments to resource ARNs. */
@ApplicationScoped
public class Ec2StoredResourceArns implements StoredResourceArns {

    private final Ec2Service ec2;

    @Inject
    public Ec2StoredResourceArns(Ec2Service ec2) {
        this.ec2 = ec2;
    }

    @Override
    public String namespace() {
        return "ec2";
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
