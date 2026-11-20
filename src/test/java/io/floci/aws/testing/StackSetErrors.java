package io.floci.aws.testing;

import io.floci.aws.core.common.AwsException;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.function.Executable;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What CloudFormation answers for a stack set that isn't there: {@code StackSetNotFoundException}, "StackSet NAME not
 * found", HTTP 404, a {@code Sender} error (measured, eu-west-1 and eu-central-1, 2026-11-17).
 */
public final class StackSetErrors {

    private StackSetErrors() {
    }

    /** Asserts that {@code call} throws that answer for {@code stackSet}. */
    public static void assertNotFound(Executable call, String stackSet) {
        AwsException missing = assertThrows(AwsException.class, call);
        assertEquals("StackSetNotFoundException", missing.getErrorCode());
        assertEquals(message(stackSet), missing.getMessage());
        assertEquals(404, missing.getHttpStatus());
    }

    /** Sends {@code request}, a CloudFormation Query request, and asserts it gets that answer for {@code stackSet}. */
    public static void assertNotFound(RequestSpecification request, String stackSet) {
        request.post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Type", equalTo("Sender"))
                .body("ErrorResponse.Error.Code", equalTo("StackSetNotFoundException"))
                .body("ErrorResponse.Error.Message", equalTo(message(stackSet)));
    }

    private static String message(String stackSet) {
        return "StackSet " + stackSet + " not found";
    }
}
