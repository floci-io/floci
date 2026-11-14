package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A JSON request is checked as the enforcement filter checks the same request made directly. */
class RequestChecksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String APPLICATION = "arn:aws:emr-serverless:us-east-1:000000000000:/applications/a";

    private static List<RequestChecks.Check> checks(String operation, String body) throws Exception {
        return RequestChecks.of(operation, MAPPER.readTree(body), "aws", "us-east-1", "000000000000", null);
    }

    @Test
    void anOperationIsCheckedOnTheResourcesItsRequestNames() throws Exception {
        assertEquals(List.of(new RequestChecks.Check("emr-serverless:StartApplication", APPLICATION, Map.of())),
                checks("emr-serverless:StartApplication", "{\"applicationId\":\"a\"}"));
        assertEquals(List.of(new RequestChecks.Check("emr-serverless:CreateApplication", "*", Map.of())),
                checks("emr-serverless:CreateApplication", "{}"));
    }

    /** A request naming none of the resources its operation acts on is checked on {@code *}, as the filter does. */
    @Test
    void aRequestNamingNoneOfItsResourcesIsCheckedOnAnyResource() throws Exception {
        assertEquals(List.of(new RequestChecks.Check("emr-serverless:StartApplication", "*", Map.of())),
                checks("emr-serverless:StartApplication", "{}"));
    }

    @Test
    void aJobRunsRoleAndTagsAreCheckedBesideItsAction() throws Exception {
        String role = "arn:aws:iam::000000000000:role/job";
        Map<String, List<String>> tagged = Map.of("aws:RequestTag/team", List.of("data"), "aws:TagKeys", List.of("team"));
        assertEquals(List.of(new RequestChecks.Check("emr-serverless:StartJobRun", APPLICATION, tagged),
                        new RequestChecks.Check("iam:PassRole", role,
                                Map.of("iam:PassedToService", List.of("emr-serverless.amazonaws.com"))),
                        new RequestChecks.Check("emr-serverless:TagResource", APPLICATION, tagged)),
                checks("emr-serverless:StartJobRun",
                        "{\"applicationId\":\"a\",\"executionRoleArn\":\"" + role + "\",\"tags\":{\"team\":\"data\"}}"));
    }

    /** An operation whose passed roles are read by hand, from a request of its own, is refused rather than misread. */
    @Test
    void anOperationPassingRolesReadByHandIsNotReadFromABody() {
        assertThrows(IllegalArgumentException.class,
                () -> checks("states:CreateStateMachine", "{\"name\":\"m\",\"roleArn\":\"arn:aws:iam::000000000000:role/r\"}"));
    }

    /** An operation the request-resource table does not read needs nothing here. */
    @Test
    void anOperationTheTableDoesNotReadNeedsNothing() throws Exception {
        JsonNode body = MAPPER.readTree("{}");
        assertEquals(List.of(), RequestChecks.of("nothing:DoesIt", body, "aws", "us-east-1", "000000000000", null));
    }
}
