package io.floci.aws.services.appsync.graphql;

import io.floci.aws.services.appsync.GraphqlSidecarClient;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SidecarSchemaCompilerTest {

    private final SidecarSchemaCompiler compiler = new SidecarSchemaCompiler(mock(GraphqlSidecarClient.class));

    @Test
    void customerAwsScalarDeclarationsAreNotDuplicated() {
        String sdl = "\"\"\"customer date scalar\"\"\"\n"
                + "scalar AWSDateTime\n"
                + "type Query { time: AWSDateTime }";

        String prepared = compiler.withDirectivesAndScalars(sdl);

        assertEquals(0, count(prepared.substring(0, prepared.lastIndexOf(sdl)), "scalar AWSDateTime"));
        assertTrue(prepared.endsWith(sdl));
        assertEquals(1, count(prepared, "scalar AWSDateTime"));
        assertEquals(1, count(prepared, "scalar AWSDate\n"));
    }

    @Test
    void scalarMentionsInCommentsAndDescriptionsDoNotSuppressInjection() {
        String sdl = "# scalar AWSDateTime\n"
                + "\"\"\"scalar AWSDateTime\"\"\"\n"
                + "type Query { description: String }";

        String prepared = compiler.withDirectivesAndScalars(sdl);

        assertEquals(1, count(prepared.substring(0, prepared.lastIndexOf(sdl)), "scalar AWSDateTime"));
        assertTrue(prepared.endsWith(sdl));
    }

    private static int count(String value, String literal) {
        Matcher matcher = Pattern.compile(Pattern.quote(literal)).matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
