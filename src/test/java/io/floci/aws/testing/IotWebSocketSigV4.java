package io.floci.aws.testing;

public final class IotWebSocketSigV4 {

    public static final String QUERY = "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260906%2Fus-east-1%2Fiotdevicegateway%2Faws4_request"
            + "&X-Amz-Date=20260906T120000Z&X-Amz-SignedHeaders=host"
            + "&X-Amz-Signature=0000000000000000000000000000000000000000000000000000000000000000";

    private IotWebSocketSigV4() {
    }
}
