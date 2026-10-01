package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
public class ApiGatewayUpdateRestApiIntegrationTest {

    @Test
    public void testUpdateRestApi() {
        String id = given()
                .contentType("application/json")
                .body("{\"name\":\"rest-api-update\",\"description\":\"before\"}")
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"updated description\"}]}";

        given()
                .contentType("application/json")
                .body(patchBody)
                .patch("/restapis/" + id)
                .then()
                .statusCode(200)
                .body("name", equalTo("rest-api-update"))
                .body("description", equalTo("updated description"));

        given()
                .get("/restapis/" + id)
                .then()
                .statusCode(200)
                .body("description", equalTo("updated description"));
    }

    @Test
    public void updateRestApiChangesTheEndpointType() {
        String id = createApi("rest-api-endpoint-type");

        patch(id, """
                [{"op":"replace","path":"/endpointConfiguration/types/REGIONAL","value":"EDGE"}]""")
                .statusCode(200)
                .body("endpointConfiguration.types", contains("EDGE"));

        // The path names the type the API has now, and a rejected patch changes nothing.
        patch(id, """
                [{"op":"replace","path":"/endpointConfiguration/types/REGIONAL","value":"PRIVATE"}]""")
                .statusCode(400);
        given().get("/restapis/" + id).then().statusCode(200)
                .body("endpointConfiguration.types", contains("EDGE"));

        // Terraform names the type by its index instead; an API has only the one at 0.
        patch(id, """
                [{"op":"replace","path":"/endpointConfiguration/types/0","value":"REGIONAL"}]""")
                .statusCode(200)
                .body("endpointConfiguration.types", contains("REGIONAL"));
        patch(id, """
                [{"op":"replace","path":"/endpointConfiguration/types/1","value":"EDGE"}]""")
                .statusCode(400);
    }

    @Test
    public void updateRestApiAssociatesAndDisassociatesVpcEndpoints() {
        String id = createApi("rest-api-vpc-endpoints");

        // A PRIVATE API needs a VPC endpoint, so the patch that makes it one associates one too.
        patch(id, """
                [{"op":"replace","path":"/endpointConfiguration/types/REGIONAL","value":"PRIVATE"},
                 {"op":"add","path":"/endpointConfiguration/vpcEndpointIds","value":"vpce-1"},
                 {"op":"add","path":"/endpointConfiguration/vpcEndpointIds","value":"vpce-2"}]""")
                .statusCode(200)
                .body("endpointConfiguration.types", contains("PRIVATE"))
                .body("endpointConfiguration.vpcEndpointIds", contains("vpce-1", "vpce-2"));

        patch(id, """
                [{"op":"remove","path":"/endpointConfiguration/vpcEndpointIds","value":"vpce-1"}]""")
                .statusCode(200)
                .body("endpointConfiguration.vpcEndpointIds", contains("vpce-2"));
        patch(id, """
                [{"op":"remove","path":"/endpointConfiguration/vpcEndpointIds","value":"vpce-2"}]""")
                .statusCode(400);

        patch(id, """
                [{"op":"replace","path":"/endpointConfiguration/types/PRIVATE","value":"REGIONAL"}]""")
                .statusCode(200)
                .body("endpointConfiguration.types", contains("REGIONAL"))
                .body("endpointConfiguration.vpcEndpointIds", empty());
    }

    private static String createApi(String name) {
        return given()
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private static ValidatableResponse patch(String id, String operations) {
        return given()
                .contentType("application/json")
                .body("{\"patchOperations\":" + operations + "}")
                .patch("/restapis/" + id)
                .then();
    }
}
