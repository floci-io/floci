package io.github.hectorvent.floci.core.common;

import io.restassured.path.xml.XmlPath;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AwsQueryResponseTest {

    @Test
    void anErrorCarriesTheMembersItsShapeDeclaresBesideTheMessage() {
        Response response = AwsQueryResponse.error("VerificationException", "Wrong <code>.",
                AwsNamespaces.SNS, 400, Map.of("Status", "Pending"));

        XmlPath error = XmlPath.from((String) response.getEntity()).setRoot("ErrorResponse.Error");
        assertEquals(400, response.getStatus());
        assertEquals("Sender", error.getString("Type"));
        assertEquals("VerificationException", error.getString("Code"));
        assertEquals("Wrong <code>.", error.getString("Message"));
        assertEquals("Pending", error.getString("Status"));
    }

    @Test
    void anErrorWithNoOtherMembersIsTheSameAsBefore() {
        Response withNone = AwsQueryResponse.error("NotFound", "Topic does not exist", AwsNamespaces.SNS, 404,
                Map.of());
        Response plain = AwsQueryResponse.error("NotFound", "Topic does not exist", AwsNamespaces.SNS, 404);

        assertEquals(withoutRequestId(plain), withoutRequestId(withNone));
    }

    private static String withoutRequestId(Response response) {
        return ((String) response.getEntity()).replaceAll("<RequestId>[^<]*</RequestId>", "");
    }
}
