package io.github.hectorvent.floci.services.iam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IamRestRoutesTest {
    @Test
    void routeDataIsBundledWithTheApplicationRatherThanOnlyWithTests() throws Exception {
        URL application = IamActionRegistry.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader resources = new URLClassLoader(new URL[]{application}, null);
                InputStream data = resources.getResourceAsStream("aws/rest-routes.json")) {
            assertNotNull(data, "Runtime IAM authorization needs the generated routes in the application artifact");
        }
    }

    @Test
    void methodPathAndNamespaceComeFromTheModelAndLiteralSegmentsKeepMatrixHandling() throws IOException {
        IamRestRoutes table = parse(snapshot("from-data", "GET", "/from.data/validation"));
        assertEquals("from-data:ValidatePolicy", table.resolve("from-data", "GET", "/from.data/validation"));
        assertEquals("from-data:ValidatePolicy",
                table.resolve("from-data", "GET", "/from.data;ignored=yes/validation;ignored=yes"));
        assertNull(table.resolve("from-data", "GET", "/fromXdata/validation"));
        assertNull(table.resolve("from-data", "POST", "/from.data/validation"));
        assertNull(table.resolve("other", "GET", "/from.data/validation"));
        assertNull(table.resolve("from-data", "POST", "/policy/validation"));
    }

    @Test
    void operationsWithoutResourceArnSupportAreNotEnrolledBySharingTheModel() throws IOException {
        String document = snapshot("from-data", "GET", "/from.data/validation")
                .replace("\"ValidatePolicy\":", "\"CreateAnalyzer\": {\"method\":\"PUT\",\"requestUri\":\"/analyzer\"},"
                        + "\"ValidatePolicy\":");
        IamRestRoutes table = parse(document);
        assertNull(table.resolve("from-data", "PUT", "/analyzer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"models\":[]}", "{\"models\":{}}"})
    void missingRouteDataFailsRatherThanProvidingAnEmptyAuthorizationTable(String document) {
        assertThrows(IOException.class, () -> parse(document));
    }

    @Test
    void theSelectedOperationAndItsRequiredFieldsMustExist() {
        assertThrows(IOException.class,
                () -> parse(snapshot("from-data", "GET", "/path").replace("ValidatePolicy", "OtherOperation")));
        assertThrows(IOException.class, () -> parse(snapshot("", "GET", "/path")));
        assertThrows(IOException.class, () -> parse(snapshot("from-data", "INVALID", "/path")));
        assertThrows(IOException.class, () -> parse(snapshot("from-data", "GET", "")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/path/{id}", "/path?query=1", "/path#fragment", "/path;matrix=x", "no-leading-slash"})
    void unsupportedEnrolledPathShapesFailRatherThanSilentlyMissingTheRoute(String path) {
        assertThrows(IOException.class, () -> parse(snapshot("from-data", "GET", path)));
    }

    private static IamRestRoutes parse(String document) throws IOException {
        try (InputStream in = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))) {
            return IamRestRoutes.parse(in);
        }
    }

    private static String snapshot(String scope, String method, String path) {
        return """
                {"models":{"accessanalyzer":{"credentialScope":"%s","operations":{
                  "ValidatePolicy":{"method":"%s","requestUri":"%s"}
                }}}}
                """.formatted(scope, method, path);
    }
}
