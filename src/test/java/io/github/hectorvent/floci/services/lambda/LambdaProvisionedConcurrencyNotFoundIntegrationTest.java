package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class LambdaProvisionedConcurrencyNotFoundIntegrationTest {
    private static final String NAME = "pc-missing-target-control";
    @Inject
    LambdaFunctionStore functions;
    @Inject
    LambdaAliasStore aliases;
    @Inject
    RegionResolver resolver;

    @BeforeEach
    void createTarget() {
        for (String version : List.of("$LATEST", "1")) {
            LambdaFunction function = LambdaFunctionTestFixtures.function(
                    resolver.getAccountId(), resolver.getRegion(), NAME, version);
            function.setRevisionId("missing-target-control-" + version);
            functions.save(resolver.getRegion(), function);
        }
        LambdaAlias alias = LambdaFunctionTestFixtures.alias(
                resolver.getAccountId(), resolver.getRegion(), NAME, "live", "1");
        aliases.save(resolver.getRegion(), alias);
    }

    @AfterEach
    void deleteTarget() {
        aliases.delete(resolver.getRegion(), NAME, "live");
        functions.delete(resolver.getRegion(), NAME);
    }

    @ParameterizedTest
    @CsvSource({
            "pc-missing-target-control, missing, Cannot find alias arn: ",
            "pc-missing-target-control, 99, Function not found: ",
            "pc-absent-function-control, missing, Cannot find alias arn: ",
            "pc-absent-function-control, 99, Function not found: "
    })
    void everyOperationReturnsTheQualifiedTarget(String name, String qualifier, String prefix) {
        String message = prefix + " " + functionArn(name) + ":" + qualifier;
        for (String reference : List.of(name, functionArn(name))) {
            String path = "/2019-09-30/functions/{function}/provisioned-concurrency";
            for (String method : List.of("GET", "PUT", "DELETE")) {
                given().contentType("application/json").queryParam("Qualifier", qualifier)
                        .pathParam("function", reference).body("{\"ProvisionedConcurrentExecutions\":1}")
                        .request(method, path).then().statusCode(404)
                        .body("__type", equalTo("ResourceNotFoundException"))
                        .body("message", equalTo(message));
            }
        }
    }

    @Test
    void listRejectsAnotherRegionWithTheSharedLambdaError() {
        String arn = AwsArnUtils.Arn.of("lambda", "us-west-2", resolver.getAccountId(),
                "function:" + NAME).toString();
        given().queryParam("List", "ALL").pathParam("function", arn)
                .get("/2019-09-30/functions/{function}/provisioned-concurrency")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Region 'us-west-2' in ARN does not match request region '"
                        + resolver.getRegion() + "'"));
    }

    @Test
    void targetOperationsRejectAnotherRegionWithTheSharedLambdaError() {
        String arn = AwsArnUtils.Arn.of("lambda", "us-west-2", resolver.getAccountId(),
                "function:" + NAME + ":live").toString();
        for (String method : List.of("GET", "PUT", "DELETE")) {
            given().contentType("application/json").queryParam("Qualifier", "live")
                    .pathParam("function", arn).body("{\"ProvisionedConcurrentExecutions\":1}")
                    .request(method, "/2019-09-30/functions/{function}/provisioned-concurrency")
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterValueException"))
                    .body("message", equalTo("Region 'us-west-2' in ARN does not match request region '"
                            + resolver.getRegion() + "'"));
        }
    }

    @Test
    void existingAliasAndVersionRetainTheirConfigurationBehavior() {
        for (String qualifier : List.of("live", "1")) {
            String path = "/2019-09-30/functions/" + NAME + "/provisioned-concurrency";
            given().queryParam("Qualifier", qualifier).get(path).then().statusCode(404)
                    .body("__type", equalTo("ProvisionedConcurrencyConfigNotFoundException"))
                    .body("message", equalTo("No Provisioned Concurrency Config found for this function"));
            given().queryParam("Qualifier", qualifier).delete(path).then().statusCode(204).body(equalTo(""));
        }
    }

    @Test
    void listWithoutQualifierReturnsAnEmptyConfigurationPage() {
        for (String reference : List.of(NAME, functionArn(NAME), resolver.getAccountId() + ":function:" + NAME)) {
            given().queryParam("List", "ALL").pathParam("function", reference)
                    .get("/2019-09-30/functions/{function}/provisioned-concurrency")
                    .then().statusCode(200)
                    .body("ProvisionedConcurrencyConfigs", equalTo(List.of()))
                    .body("NextMarker", nullValue());
        }
    }

    @Test
    void listMissingFunctionUsesTheFunctionNotFoundError() {
        given().queryParam("List", "ALL")
                .get("/2019-09-30/functions/pc-absent-function-control/provisioned-concurrency")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getWithoutQualifierStillRejectsTheRequest() {
        given().get("/2019-09-30/functions/" + NAME + "/provisioned-concurrency")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    private String functionArn(String name) {
        return resolver.buildArn("lambda", resolver.getRegion(), "function:" + name);
    }
}
