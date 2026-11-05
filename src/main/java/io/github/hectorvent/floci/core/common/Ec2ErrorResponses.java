package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.iam.RequestBodyReader;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/** EC2's error envelope differs from the other Query services. */
public final class Ec2ErrorResponses {

    private Ec2ErrorResponses() {
    }

    static boolean isEc2QueryRequest(ContainerRequestContext ctx) {
        if (!(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim)
                || claim.protocol() != WireProtocol.AWS_QUERY) {
            return false;
        }
        if (claim.service() != null) {
            return "ec2".equals(claim.service().externalKey());
        }
        try {
            String action = AwsQueryServiceResolver.action(RequestBodyReader.formField(ctx, "Action"),
                    RequestBodyReader.formField(ctx, "Operation"));
            return action != null && "ec2".equals(AwsQueryController.inferServiceFromAction(action,
                    RequestBodyReader.formField(ctx, "Version")));
        } catch (IllegalArgumentException ignored) {
            // A malformed form cannot identify a service; retain the generic protocol error.
            return false;
        }
    }

    public static Response authFailure() {
        return of("AuthFailure", "AWS was not able to validate the provided access credentials", 401);
    }

    public static Response of(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("Response")
                .start("Errors")
                .start("Error")
                .elem("Code", code)
                .elem("Message", message)
                .end("Error")
                .end("Errors")
                .elem("RequestID", UUID.randomUUID().toString())
                .end("Response")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
