package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class ApiGatewayV2RouteSelectionServiceTest {

    private static final String REGION = "us-east-1";
    private static final String API_ID = "api-id";

    @Test
    void narrowerGreedyRouteWinsRegardlessOfStorageOrder() {
        Route broad = route("GET /api/{proxy+}", "JWT");
        Route narrow = route("GET /api/v1/customerid/{proxy+}", "NONE");

        assertSame(narrow, select(List.of(broad, narrow), "GET", "/api/v1/customerid/items"));
        assertSame(narrow, select(List.of(narrow, broad), "GET", "/api/v1/customerid/items"));
    }

    @Test
    void parameterRouteWinsOverGreedyRoute() {
        Route greedy = route("GET /pets/{proxy+}", "JWT");
        Route parameter = route("GET /pets/dog/{id}", "NONE");

        assertSame(parameter, select(List.of(greedy, parameter), "GET", "/pets/dog/42"));
    }

    @Test
    void literalPathWinsOverParameterRoute() {
        Route parameter = route("GET /pets/{id}", "JWT");
        Route literal = route("GET /pets/dog", "NONE");

        assertSame(literal, select(List.of(parameter, literal), "GET", "/pets/dog"));
    }

    @Test
    void methodSpecificRouteWinsForSameGreedyPath() {
        Route any = route("ANY /open/{proxy+}", "JWT");
        Route get = route("GET /open/{proxy+}", "NONE");

        assertSame(get, select(List.of(any, get), "GET", "/open/items"));
        assertSame(any, select(List.of(any, get), "POST", "/open/items"));
    }

    @Test
    void petsGreedyRouteWinsOverCatchAllAnyRoute() {
        Route catchAll = route("ANY /{proxy+}", "JWT");
        Route pets = route("GET /pets/{proxy+}", "NONE");

        assertSame(pets, select(List.of(catchAll, pets), "GET", "/pets/cat/1"));
        assertSame(pets, select(List.of(pets, catchAll), "GET", "/pets/cat/1"));
    }

    @Test
    void exactAnyRouteWinsOverBroaderGreedyRoute() {
        Route greedy = route("GET /pets/{proxy+}", "JWT");
        Route exactAny = route("ANY /pets/cat", "NONE");

        assertSame(exactAny, select(List.of(greedy, exactAny), "GET", "/pets/cat"));
        assertSame(exactAny, select(List.of(exactAny, greedy), "GET", "/pets/cat"));
    }

    @Test
    void defaultRouteIsUsedOnlyWhenNoPathMatches() {
        Route fallback = route("$default", "JWT");
        Route specific = route("GET /open/{proxy+}", "NONE");

        assertSame(specific, select(List.of(fallback, specific), "GET", "/open/items"));
        assertSame(fallback, select(List.of(fallback, specific), "GET", "/closed/items"));
    }

    @Test
    void noMatchingRouteReturnsNull() {
        Route get = route("GET /open/{proxy+}", "NONE");

        assertNull(select(List.of(get), "POST", "/open/items"));
    }

    private static Route select(List<Route> routes, String method, String path) {
        ApiGatewayV2Service service = mock(ApiGatewayV2Service.class, CALLS_REAL_METHODS);
        doReturn(routes).when(service).getRoutes(REGION, API_ID);
        return service.findMatchingRoute(REGION, API_ID, method, path);
    }

    private static Route route(String key, String authorizationType) {
        Route route = new Route();
        route.setRouteKey(key);
        route.setAuthorizationType(authorizationType);
        return route;
    }
}
