package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SesV1UnsupportedOperationIntegrationTest {

    @Test
    void namesTheOperation() {
        SesV1Requests.query("NoSuchOperation")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnsupportedOperation"))
            .body("ErrorResponse.Error.Message", equalTo("Operation NoSuchOperation is not supported by SES."));
    }

    // the action is request input like any quoted value, so it gets the same treatment in the message;
    // without the UTF-8 charset RestAssured sends U+FFFE as '?'
    @Test
    void spellsOutACharacterXmlForbids() {
        SesV1Requests.query("No\tSuch\uFFFEOperation")
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnsupportedOperation"))
            .body("ErrorResponse.Error.Message",
                    equalTo("Operation No0x0009Such0xfffeOperation is not supported by SES."));
    }
}
