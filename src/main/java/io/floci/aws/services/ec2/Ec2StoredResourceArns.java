package io.floci.aws.services.ec2;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/** Resolves the caller's launch-template names to their stored ID-based ARNs. */
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
    public Optional<String> find(String type, List<String> names, String region) {
        return "launch-template".equals(type) && names.size() == 1
                ? ec2.findLaunchTemplateArn(region, names.getFirst()) : Optional.empty();
    }
}
