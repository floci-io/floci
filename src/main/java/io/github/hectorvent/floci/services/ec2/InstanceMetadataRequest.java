package io.github.hectorvent.floci.services.ec2;

/**
 * The MetadataOptions.* arguments of a RunInstances (or
 * ModifyInstanceMetadataOptions) request. Each field is null when the
 * request did not specify it - AWS's own per-field default then applies,
 * which is why this is not simply a plain Instance-shaped object: a null
 * here must not overwrite an existing instance's value on a partial modify.
 */
public record InstanceMetadataRequest(
        String httpTokens,
        Integer httpPutResponseHopLimit,
        String httpEndpoint,
        String httpProtocolIpv6,
        String instanceMetadataTags) {

    public static final InstanceMetadataRequest EMPTY =
            new InstanceMetadataRequest(null, null, null, null, null);

    public boolean isEmpty() {
        return httpTokens == null && httpPutResponseHopLimit == null && httpEndpoint == null
                && httpProtocolIpv6 == null && instanceMetadataTags == null;
    }
}
