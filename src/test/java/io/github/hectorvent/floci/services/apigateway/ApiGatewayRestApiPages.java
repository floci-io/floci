package io.github.hectorvent.floci.services.apigateway;

import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

public final class ApiGatewayRestApiPages {
    private ApiGatewayRestApiPages() {
    }

    public static List<Map<String, Object>> restApis() {
        List<Map<String, Object>> apis = new ArrayList<>();
        String position = null;
        do {
            RequestSpecification request = given();
            if (position != null) {
                request.queryParam("position", position);
            }
            JsonPath page = request.get("/restapis").then().statusCode(200).extract().jsonPath();
            apis.addAll(page.getList("item"));
            position = page.getString("position");
        } while (position != null);
        return apis;
    }

    public static List<String> restApiIds() {
        return restApis().stream().map(api -> (String) api.get("id")).toList();
    }

    public static List<String> restApiNames() {
        return restApis().stream().map(api -> (String) api.get("name")).toList();
    }

    public static Map<String, Object> restApi(String id) {
        return restApis().stream().filter(api -> id.equals(api.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("Missing REST API: " + id));
    }
}
