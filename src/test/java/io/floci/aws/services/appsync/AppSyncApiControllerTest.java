package io.floci.aws.services.appsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AppSyncApiControllerTest {

    private final AppSyncService service = mock(AppSyncService.class);
    private final AppSyncApiController controller =
            new AppSyncApiController(service, mock(RegionResolver.class), new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"name\":\"x\"}]", "\"name\"", "42", "true", "null", "{\"name\":"})
    void aBodyThatIsNotAJsonObjectIsABadRequest(String body) {
        AwsException created = assertThrows(AwsException.class,
                () -> controller.createApi(mock(HttpHeaders.class), body));
        AwsException updated = assertThrows(AwsException.class, () -> controller.updateApi("abc123", body));

        assertEquals("BadRequestException", created.getErrorCode());
        assertEquals(400, created.getHttpStatus());
        assertEquals("BadRequestException", updated.getErrorCode());
        verifyNoInteractions(service);
    }
}
