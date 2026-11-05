package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/** EC2's error envelope differs from the other Query services. */
public final class Ec2ErrorResponses {

    private Ec2ErrorResponses() {
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
