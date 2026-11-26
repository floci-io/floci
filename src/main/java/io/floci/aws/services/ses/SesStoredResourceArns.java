package io.floci.aws.services.ses;

import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.ses.model.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks SES of the resources a request names: the ARN the caller's tenant of the name a request gives is
 * stored under, whose last part is the id SES minted for it, which the name alone does not carry.
 */
@ApplicationScoped
public class SesStoredResourceArns implements StoredResourceArns {

    private static final String TENANT = "tenant";

    private final SesTenantService tenants;

    @Inject
    public SesStoredResourceArns(SesTenantService tenants) {
        this.tenants = tenants;
    }

    @Override
    public String namespace() {
        return "ses";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!TENANT.equals(type) || names.size() != 1 || names.getFirst() == null) {
            return Optional.empty();
        }
        return tenants.find(names.getFirst(), region).map(Tenant::tenantArn);
    }
}
