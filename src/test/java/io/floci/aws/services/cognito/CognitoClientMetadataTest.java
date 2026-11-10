package io.floci.aws.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cognito.model.CognitoUser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.invocation.Invocation;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class CognitoClientMetadataTest {

    @ParameterizedTest
    @MethodSource("metadataRequests")
    void handlersPassClientMetadataToTheService(String action, String body, Map<String, String> expected)
            throws Exception {
        CognitoService service = mock(CognitoService.class, invocation -> {
            if (invocation.getMethod().getReturnType() == CognitoUser.class) {
                return new CognitoUser();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        ObjectMapper mapper = new ObjectMapper();
        CognitoJsonHandler handler = new CognitoJsonHandler(service, mapper);

        handler.handle(action, mapper.readTree(body), "us-east-1");

        Collection<Invocation> invocations = mockingDetails(service).getInvocations();
        assertEquals(1, invocations.size());
        Object[] arguments = invocations.iterator().next().getArguments();
        assertEquals(expected, arguments[arguments.length - 1]);
    }

    private static Stream<Arguments> metadataRequests() {
        String[] actions = {"AdminCreateUser", "InitiateAuth", "AdminInitiateAuth", "RespondToAuthChallenge",
                "AdminRespondToAuthChallenge", "ConfirmSignUp", "AdminConfirmSignUp", "ConfirmForgotPassword"};
        return Arrays.stream(actions).flatMap(action -> Stream.of(
                Arguments.of(action, "{}", Map.of()),
                Arguments.of(action, "{\"ClientMetadata\":null}", Map.of()),
                Arguments.of(action, "{\"ClientMetadata\":{}}", Map.of()),
                Arguments.of(action, "{\"ClientMetadata\":[]}", Map.of()),
                Arguments.of(action, "{\"ClientMetadata\":\"ignored\"}", Map.of()),
                Arguments.of(action, "{\"ClientMetadata\":{\"source\":\"cli\",\"attempt\":2,\"enabled\":true}}",
                        Map.of("source", "cli", "attempt", "2", "enabled", "true"))));
    }
}
