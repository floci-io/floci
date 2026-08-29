package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Route {

    private String destinationCidrBlock;
    private String destinationIpv6CidrBlock;
    private String destinationPrefixListId;
    private String gatewayId;
    private String natGatewayId;
    private String egressOnlyInternetGatewayId;
    private String state = "active";
    private String origin;

    public Route() {}

    public Route(String destinationCidrBlock, String gatewayId, String origin) {
        setDestinationCidrBlock(destinationCidrBlock);
        this.gatewayId = gatewayId;
        this.origin = origin;
    }

    public String getDestinationCidrBlock() { return destinationCidrBlock; }

    /**
     * Canonicalized on every set, not only via the constructor. {@link Ec2Service} already
     * canonicalizes a {@code DestinationCidrBlock} before constructing a {@code Route} at the
     * API boundary (CreateRoute/ReplaceRoute), so re-canonicalizing here is a no-op for that
     * path. The path this setter actually fixes is Jackson deserialization: PersistentStorage
     * reconstructs a {@code Route} from disk via this setter, so a route written before
     * canonicalization existed (e.g. stored as {@code 100.68.0.18/18}) is normalized the moment
     * it is read back into memory, and every later in-memory comparison
     * ({@code matchesDestination} in CreateRoute/ReplaceRoute/DeleteRoute) is apples-to-apples
     * with a freshly-canonicalized incoming destination.
     *
     * <p>Scoped to IPv4 only, matching the API-boundary decision: a value containing {@code ':'}
     * (or anything else {@link CidrCanonicalizer} cannot parse) is left untouched, since
     * DestinationCidrBlock is documented by AWS as an IPv4-only field and
     * DestinationIpv6CidrBlock is a separate member with its own field/setter.
     */
    public void setDestinationCidrBlock(String destinationCidrBlock) {
        if (destinationCidrBlock == null || destinationCidrBlock.isBlank() || destinationCidrBlock.contains(":")) {
            this.destinationCidrBlock = destinationCidrBlock;
            return;
        }
        this.destinationCidrBlock = CidrCanonicalizer.canonicalize(destinationCidrBlock).orElse(destinationCidrBlock);
    }

    public String getDestinationIpv6CidrBlock() { return destinationIpv6CidrBlock; }
    public void setDestinationIpv6CidrBlock(String destinationIpv6CidrBlock) { this.destinationIpv6CidrBlock = destinationIpv6CidrBlock; }

    public String getDestinationPrefixListId() { return destinationPrefixListId; }
    public void setDestinationPrefixListId(String destinationPrefixListId) { this.destinationPrefixListId = destinationPrefixListId; }

    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }

    public String getNatGatewayId() { return natGatewayId; }
    public void setNatGatewayId(String natGatewayId) { this.natGatewayId = natGatewayId; }

    public String getEgressOnlyInternetGatewayId() { return egressOnlyInternetGatewayId; }
    public void setEgressOnlyInternetGatewayId(String egressOnlyInternetGatewayId) { this.egressOnlyInternetGatewayId = egressOnlyInternetGatewayId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
