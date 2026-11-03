package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.OpenSearchVpcEndpoint;
import io.github.hectorvent.floci.services.opensearch.model.VpcOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * OpenSearch Service-managed VPC endpoints, scoped to an account and a region like the domains
 * they lead to. An endpoint is stored {@code ACTIVE}: the create, update and delete responses carry
 * the transitional status AWS answers with, and the next read already sees the settled one.
 */
@ApplicationScoped
public class OpenSearchVpcEndpointService {

    private static final Logger LOG = Logger.getLogger(OpenSearchVpcEndpointService.class);

    static final String ENDPOINT_NOT_FOUND = "ENDPOINT_NOT_FOUND";
    // Both captured from DescribeVpcEndpoints against AWS for an id that does not exist.
    static final String ENDPOINT_NOT_FOUND_MESSAGE = "Endpoint does not exist";
    private static final String ID_PATTERN = "^aos-[a-zA-Z0-9]*$";
    private static final Pattern ID = Pattern.compile(ID_PATTERN);
    private static final int ID_MIN = 5;
    private static final int ID_MAX = 256;

    private final AccountAwareStorageBackend<OpenSearchVpcEndpoint> endpoints;
    private final OpenSearchService domains;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;

    @Inject
    public OpenSearchVpcEndpointService(StorageFactory storageFactory, OpenSearchService domains,
                                        RegionResolver regionResolver, Ec2Service ec2Service) {
        this(storageFactory.create("opensearch", "opensearch-vpc-endpoints.json",
                new TypeReference<Map<String, OpenSearchVpcEndpoint>>() {}), domains, regionResolver, ec2Service);
    }

    OpenSearchVpcEndpointService(AccountAwareStorageBackend<OpenSearchVpcEndpoint> endpoints,
                                 OpenSearchService domains, RegionResolver regionResolver, Ec2Service ec2Service) {
        this.endpoints = endpoints;
        this.domains = domains;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
    }

    /** One {@code VpcEndpointErrors} entry. */
    public record EndpointError(String vpcEndpointId, String errorCode, String errorMessage) {}

    /** The endpoints found and an error for each id that names none, as {@code DescribeVpcEndpoints} answers. */
    public record DescribeResult(List<OpenSearchVpcEndpoint> endpoints, List<EndpointError> errors) {}

    /**
     * Creates an endpoint for the domain the ARN names. A retry carrying the same {@code ClientToken}
     * for the same domain answers the endpoint the first call created.
     */
    public synchronized OpenSearchVpcEndpoint create(String domainArn, VpcOptions requested, Boolean egressEnabled,
                                                     String clientToken, String region) {
        List<String> violations = new ArrayList<>();
        if (domainArn == null || domainArn.isBlank()) {
            violations.add(SmithyValidationErrors.notNull("domainArn"));
        }
        if (requested == null) {
            violations.add(SmithyValidationErrors.notNull("vpcOptions"));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        // The endpoint is kept in the request's Region, so a domain of another Region is refused as
        // unknown: neither Region's ListVpcEndpointsForDomain could otherwise find the endpoint.
        Domain domain = domains.findDomainByArn(domainArn)
                .filter(found -> region.equals(AwsArnUtils.parse(found.getArn()).region()))
                .orElseThrow(() -> new AwsException("ValidationException",
                        "Domain not found for ARN: " + domainArn, 400));
        if (clientToken != null && !clientToken.isBlank()) {
            Optional<OpenSearchVpcEndpoint> earlier = inRegion(region).stream()
                    .filter(endpoint -> clientToken.equals(endpoint.getClientToken())
                            && domain.getArn().equals(endpoint.getDomainArn()))
                    .findFirst();
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }

        OpenSearchVpcEndpoint endpoint = new OpenSearchVpcEndpoint();
        endpoint.setVpcEndpointId("aos-" + newIdSuffix());
        endpoint.setVpcEndpointOwner(regionResolver.getAccountId());
        endpoint.setDomainArn(domain.getArn());
        endpoint.setVpcOptions(resolved(requested, region));
        endpoint.setEgressEnabled(egressEnabled);
        endpoint.setClientToken(clientToken);
        endpoint.setStatus("ACTIVE");
        endpoints.put(storageKey(region, endpoint.getVpcEndpointId()), endpoint);
        LOG.infov("Created OpenSearch VPC endpoint {0} for {1}", endpoint.getVpcEndpointId(), domain.getArn());
        return endpoint;
    }

    public DescribeResult describe(List<String> vpcEndpointIds, String region) {
        if (vpcEndpointIds == null) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("vpcEndpointIds"));
        }
        // AWS checks the list as a whole, quoting all of it, as captured for one malformed id.
        if (vpcEndpointIds.stream().anyMatch(id -> !validId(id))) {
            throw SmithyValidationErrors.validationException("Value '" + vpcEndpointIds
                    + "' at 'vpcEndpointIds' failed to satisfy constraint: Member must satisfy constraint: "
                    + SmithyValidationErrors.constraintSet(ID_MIN, ID_MAX, ID_PATTERN, null));
        }
        List<OpenSearchVpcEndpoint> found = new ArrayList<>();
        List<EndpointError> errors = new ArrayList<>();
        for (String id : new LinkedHashSet<>(vpcEndpointIds)) {
            Optional<OpenSearchVpcEndpoint> endpoint = endpoints.get(storageKey(region, id));
            if (endpoint.isPresent()) {
                found.add(endpoint.get());
            } else {
                errors.add(new EndpointError(id, ENDPOINT_NOT_FOUND, ENDPOINT_NOT_FOUND_MESSAGE));
            }
        }
        return new DescribeResult(found, errors);
    }

    /** Replaces the subnets, the security groups and the egress setting the request names. */
    public synchronized OpenSearchVpcEndpoint update(String vpcEndpointId, VpcOptions requested,
                                                     Boolean egressEnabled, String region) {
        List<String> violations = new ArrayList<>(idViolations(vpcEndpointId));
        if (requested == null) {
            violations.add(SmithyValidationErrors.notNull("vpcOptions"));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        OpenSearchVpcEndpoint endpoint = require(vpcEndpointId, region);
        endpoint.setVpcOptions(resolved(requested, region));
        if (egressEnabled != null) {
            endpoint.setEgressEnabled(egressEnabled);
        }
        endpoints.put(storageKey(region, vpcEndpointId), endpoint);
        return endpoint;
    }

    public synchronized OpenSearchVpcEndpoint delete(String vpcEndpointId, String region) {
        List<String> violations = idViolations(vpcEndpointId);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        OpenSearchVpcEndpoint endpoint = require(vpcEndpointId, region);
        endpoints.delete(storageKey(region, vpcEndpointId));
        LOG.infov("Deleted OpenSearch VPC endpoint {0}", vpcEndpointId);
        return endpoint;
    }

    /** Every endpoint in the caller's account and region, in id order. */
    public List<OpenSearchVpcEndpoint> list(String region) {
        return inRegion(region);
    }

    /**
     * The endpoints of the domain called {@code domainName}. A name no domain carries lists nothing
     * rather than failing, as AWS was captured answering.
     */
    public List<OpenSearchVpcEndpoint> listForDomain(String domainName, String region) {
        Optional<Domain> domain = domains.findDomain(domainName, region);
        if (domain.isEmpty()) {
            return List.of();
        }
        String domainArn = domain.get().getArn();
        return inRegion(region).stream()
                .filter(endpoint -> domainArn.equals(endpoint.getDomainArn()))
                .toList();
    }

    /** The address a client reaches the endpoint's domain at, which in Floci is the domain's own. */
    public Optional<String> endpointAddress(OpenSearchVpcEndpoint endpoint) {
        return domains.findDomainByArn(endpoint.getDomainArn())
                .map(Domain::getEndpoint)
                .filter(address -> !address.isEmpty());
    }

    private OpenSearchVpcEndpoint require(String vpcEndpointId, String region) {
        return endpoints.get(storageKey(region, vpcEndpointId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", ENDPOINT_NOT_FOUND_MESSAGE, 409));
    }

    private List<OpenSearchVpcEndpoint> inRegion(String region) {
        return endpoints.scan(key -> key.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(OpenSearchVpcEndpoint::getVpcEndpointId))
                .toList();
    }

    /**
     * The requested subnets and security groups, with the VPC and zones EC2 knows the subnets by. A
     * subnet EC2 does not know is kept as given and left unresolved. Without security groups the
     * endpoint takes the VPC's default one, as AWS does, when EC2 knows the VPC and still has it.
     */
    private VpcOptions resolved(VpcOptions requested, String region) {
        VpcOptions options = new VpcOptions();
        options.setSubnetIds(new ArrayList<>(requested.getSubnetIds()));
        options.setSecurityGroupIds(new ArrayList<>(requested.getSecurityGroupIds()));
        if (ec2Service != null && !requested.getSubnetIds().isEmpty()) {
            Map<String, Subnet> known = new HashMap<>();
            ec2Service.describeSubnets(region, requested.getSubnetIds(), Map.of())
                    .forEach(subnet -> known.put(subnet.getSubnetId(), subnet));
            // In the order the subnets were requested.
            Set<String> zones = new LinkedHashSet<>();
            for (String subnetId : requested.getSubnetIds()) {
                Subnet subnet = known.get(subnetId);
                if (subnet != null) {
                    zones.add(subnet.getAvailabilityZone());
                    if (options.getVpcId() == null) {
                        options.setVpcId(subnet.getVpcId());
                    }
                }
            }
            options.setAvailabilityZones(new ArrayList<>(zones));
            if (options.getSecurityGroupIds().isEmpty() && options.getVpcId() != null) {
                ec2Service.defaultSecurityGroupOf(region, options.getVpcId())
                        .ifPresent(group -> options.setSecurityGroupIds(new ArrayList<>(List.of(group.getGroupId()))));
            }
        }
        return options;
    }

    private static List<String> idViolations(String vpcEndpointId) {
        if (vpcEndpointId == null || vpcEndpointId.isEmpty()) {
            return List.of(SmithyValidationErrors.notNull("vpcEndpointId"));
        }
        List<String> violations = new ArrayList<>();
        String length = SmithyValidationErrors.lengthOutOfRange("vpcEndpointId", vpcEndpointId, ID_MIN, ID_MAX);
        if (length != null) {
            violations.add(length);
        }
        if (!ID.matcher(vpcEndpointId).matches()) {
            violations.add(SmithyValidationErrors.pattern("vpcEndpointId", vpcEndpointId, ID_PATTERN));
        }
        return violations;
    }

    private static boolean validId(String id) {
        return id != null && id.length() >= ID_MIN && id.length() <= ID_MAX && ID.matcher(id).matches();
    }

    /**
     * Twelve hexadecimal digits, the form AWS endpoint ids take. An id is no secret, and a random
     * held in a static field would be fixed into the native image at build time.
     */
    private static String newIdSuffix() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String storageKey(String region, String vpcEndpointId) {
        return region + "::" + vpcEndpointId;
    }
}
