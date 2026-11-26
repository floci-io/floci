package io.floci.aws.services.ses;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.ses.mime.SesRawMessage;
import io.floci.aws.services.ses.model.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks SES of the resources a request names: the ARN the caller's tenant of the name a request gives is
 * stored under, whose last part is the id SES minted for it, which the name alone does not carry; and the identity a
 * send's address, or the "From" header of its raw message, sends as.
 */
@ApplicationScoped
public class SesStoredResourceArns implements StoredResourceArns {

    private static final String TENANT = "tenant";
    private static final String IDENTITY = "identity";
    private static final String RAW_MESSAGE_DATA = ".Data";

    private final SesTenantService tenants;
    private final SesIdentityService identities;
    private final RegionResolver regionResolver;

    @Inject
    public SesStoredResourceArns(SesTenantService tenants, SesIdentityService identities,
                                 RegionResolver regionResolver) {
        this.tenants = tenants;
        this.identities = identities;
        this.regionResolver = regionResolver;
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

    /**
     * The identity an address sends as, the verified one {@link SesIdentityService#verifiedIdentityOf} gives, or the
     * address's own when none is: the address a member names, or the first "From" address of the raw message the
     * member holds.
     */
    @Override
    public Optional<String> findByMembers(String type, List<String> members, List<String> names, String region) {
        if (!IDENTITY.equals(type) || members.size() != 1 || names.size() != 1 || names.getFirst() == null) {
            return find(type, names, region);
        }
        String value = names.getFirst();
        Optional<String> address = members.getFirst().endsWith(RAW_MESSAGE_DATA)
                ? SesRawMessage.parseRawHeaders(value).fromAddresses().stream().findFirst()
                : Optional.of(SesService.extractEmailAddress(value));
        return address.filter(found -> !found.isBlank())
                .map(found -> identities.verifiedIdentityOf(found, region).orElse(found))
                .map(identity -> regionResolver.buildArn("ses", region, "identity/" + identity));
    }
}
