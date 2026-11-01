package io.github.hectorvent.floci.services.route53;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public final class Route53HostedZoneTestSupport {

    private Route53HostedZoneTestSupport() {}

    public static String createZone(String name, String callerReference) {
        return createZone(name, callerReference, null);
    }

    public static String createZone(String name, String callerReference, String authorization) {
        String request = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                </CreateHostedZoneRequest>
                """.formatted(name, callerReference);
        RequestSpecification specification = given().contentType("application/xml").body(request);
        if (authorization != null) {
            specification.header("Authorization", authorization);
        }
        String location = specification
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
