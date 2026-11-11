package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AthenaRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void creationAllowsAnOmittedOrNullTokenAndValidatesProvidedTokens() throws Exception {
        JsonNode request = json("{\"Name\":\"saved\",\"Database\":\"db\",\"QueryString\":\"select 1\"}");
        assertDoesNotThrow(() -> AthenaRequestShapes.check("CreateNamedQuery", request));
        assertDoesNotThrow(() -> AthenaRequestShapes.check("CreateNamedQuery", json(
                "{\"Name\":\"saved\",\"Database\":\"db\",\"QueryString\":\"select 1\",\"ClientRequestToken\":null}")));
        refusal("CreateNamedQuery", "{\"Name\":\"saved\",\"Database\":\"db\",\"QueryString\":\"select 1\","
                + "\"ClientRequestToken\":\"short\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateNamedQuery", "GetNamedQuery", "BatchGetNamedQuery", "UpdateNamedQuery",
            "DeleteNamedQuery", "GetWorkGroup", "GetDataCatalog", "ListDatabases", "GetTableMetadata"})
    void modelRequiredFieldsAreEnforced(String operation) throws Exception {
        refusal(operation, "{}");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ListNamedQueries|{\"MaxResults\":51}",
            "ListNamedQueries|{\"MaxResults\":\"1\"}",
            "ListNamedQueries|{\"NextToken\":false}",
            "ListNamedQueries|{\"WorkGroup\":\"bad group\"}",
            "GetNamedQuery|{\"NamedQueryId\":\"has space\"}",
            "BatchGetNamedQuery|{\"NamedQueryIds\":[]}",
            "BatchGetNamedQuery|{\"NamedQueryIds\":[null]}",
            "CreateWorkGroup|{\"Name\":\"team\",\"Configuration\":{\"EnforceWorkGroupConfiguration\":1}}",
            "CreateWorkGroup|{\"Name\":\"team\",\"Tags\":[{\"Key\":4,\"Value\":\"value\"}]}",
            "CreateDataCatalog|{\"Name\":\"catalog\",\"Type\":\"GLUE\",\"Parameters\":{\"catalog-id\":4}}",
            "CreateDataCatalog|{\"Name\":\"catalog\",\"Type\":\"UNKNOWN\"}",
            "DeleteWorkGroup|{\"WorkGroup\":\"team\",\"RecursiveDeleteOption\":\"true\"}",
            "ListEngineVersions|{\"MaxResults\":\"1\"}"
    })
    void typesBoundsPatternsAndEnumsAreCheckedAcrossAthenaOperations(String operation, String body) throws Exception {
        refusal(operation, body);
    }

    @Test
    void modelStringLengthsCountUnicodeCodePoints() throws Exception {
        String name = "\uD83D\uDE00".repeat(128);
        assertDoesNotThrow(() -> AthenaRequestShapes.check("CreateNamedQuery", json(
                "{\"Name\":\"" + name + "\",\"Database\":\"db\",\"QueryString\":\"select 1\"}")));
        refusal("CreateNamedQuery", "{\"Name\":\"" + name + "a\",\"Database\":\"db\",\"QueryString\":\"select 1\"}");
    }

    private static void refusal(String operation, String body) throws Exception {
        JsonNode request = json(body);
        AwsException failure = assertThrows(AwsException.class, () -> AthenaRequestShapes.check(operation, request));
        assertEquals("InvalidRequestException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }
}
