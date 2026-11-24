package io.floci.aws.services.route53resolver;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks Route 53 Resolver of the resources a request names: the ARN of the managed firewall domain list an id
 * names, which AWS owns rather than the caller, so a request naming one is checked on the ARN the service gives it.
 */
@ApplicationScoped
public class Route53ResolverStoredResourceArns implements StoredResourceArns {

    private final Route53ResolverService resolver;

    @Inject
    public Route53ResolverStoredResourceArns(Route53ResolverService resolver) {
        this.resolver = resolver;
    }

    @Override
    public String namespace() {
        return "route53resolver";
    }

    /**
     * The ARN of the managed firewall domain list the id names; none for any other id, a custom list's among them,
     * which is the caller's own.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"firewall-domain-list".equals(type) || names.size() != 1 || names.getFirst() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(resolver.getFirewallDomainList(region, names.getFirst()).arn());
        } catch (AwsException expected) {
            // Not a managed list: a custom one, or none, which the rendered ARN in the caller's account names.
            return Optional.empty();
        }
    }
}
