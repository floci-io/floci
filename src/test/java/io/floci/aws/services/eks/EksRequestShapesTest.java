package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksRequestShapesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"1", "0", "1.5"})
    void aNumberForABooleanMemberIsRefused(String value) {
        AwsException error = assertThrows(AwsException.class, () -> EksRequestShapes.check("CreateCluster",
                mapper.readTree("{\"resourcesVpcConfig\":{\"endpointPublicAccess\":" + value + "}}")));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains("Boolean"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "\"TRUE\"", "\"false\"", "null"})
    void aBooleanItsTextOrNullPasses(String value) {
        assertDoesNotThrow(() -> EksRequestShapes.check("CreateCluster",
                mapper.readTree("{\"accessConfig\":{\"bootstrapClusterCreatorAdminPermissions\":" + value + "}}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"public\"", "[]"})
    void aScalarOrListForAStructureIsRefused(String value) {
        AwsException error = assertThrows(AwsException.class, () -> EksRequestShapes.check("CreateCluster",
                mapper.readTree("{\"name\":\"c\",\"resourcesVpcConfig\":" + value + "}")));
        assertEquals("SerializationException", error.getErrorCode());
    }
}
