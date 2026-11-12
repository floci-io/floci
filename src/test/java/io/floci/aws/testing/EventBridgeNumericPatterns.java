package io.floci.aws.testing;

import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

/** Numeric pattern cases shared by the EventBridge service and SDK tests. */
public final class EventBridgeNumericPatterns {

    private EventBridgeNumericPatterns() {
    }

    public static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("7", "\">=\",7", true),
                Arguments.of("7", "\">=\",6", true),
                Arguments.of("6", "\">=\",7", false),
                Arguments.of("7", "\">\",7", false),
                Arguments.of("8", "\">\",7", true),
                Arguments.of("6", "\"<\",7", true),
                Arguments.of("7", "\"<\",7", false),
                Arguments.of("7", "\"<=\",7", true),
                Arguments.of("8", "\"<=\",7", false),
                Arguments.of("7.0", "\"=\",7", true),
                Arguments.of("3.018e2", "\"=\",301.8", true),
                Arguments.of("7", "\"=\",8", false),
                Arguments.of("-2.5", "\">\",-3,\"<=\",0", true),
                Arguments.of("0", "\">\",-3,\"<=\",0", true),
                Arguments.of("-3", "\">\",-3,\"<=\",0", false),
                Arguments.of("1", "\">\",-3,\"<=\",0", false),
                Arguments.of("-0.0", "\"=\",0", true),
                Arguments.of("\"7\"", "\">=\",7", false),
                Arguments.of("true", "\">=\",0", false),
                Arguments.of("null", "\">=\",0", false),
                Arguments.of("{}", "\">=\",0", false),
                Arguments.of("[3,7]", "\">=\",7", true),
                Arguments.of("[3,6]", "\">=\",7", false),
                Arguments.of("[3,9]", "\">=\",7,\"<\",9", false),
                Arguments.of("[3,8]", "\">=\",7,\"<\",9", true),
                Arguments.of("[\"7\"]", "\">=\",7", false),
                Arguments.of("[]", "\">=\",7", false),
                Arguments.of("6", "\">\",6,\"<\",8", false),
                Arguments.of("7", "\">\",6,\"<\",8", true),
                Arguments.of("8", "\">\",6,\"<\",8", false),
                Arguments.of("6", "\">\",6,\"<=\",8", false),
                Arguments.of("7", "\">\",6,\"<=\",8", true),
                Arguments.of("8", "\">\",6,\"<=\",8", true),
                Arguments.of("6", "\">=\",6,\"<\",8", true),
                Arguments.of("7", "\">=\",6,\"<\",8", true),
                Arguments.of("8", "\">=\",6,\"<\",8", false),
                Arguments.of("6", "\">=\",6,\"<=\",8", true),
                Arguments.of("7", "\">=\",6,\"<=\",8", true),
                Arguments.of("8", "\">=\",6,\"<=\",8", true),
                Arguments.of("8", "\"<\",7", false),
                Arguments.of("301.8", "\"=\",3.018e2", true),
                Arguments.of("7", "\">\",6,\"<=\",7", true),
                Arguments.of("8", "\">\",6,\"<=\",7", false));
    }

    public static Stream<String> invalidPatterns() {
        Stream<String> invalidFilters = Stream.of("null", "7", "{}", "[]", "[\">=\"]", "[\">=\",7,\"<\"]",
                "[\"!=\",7]", "[7,7]", "[\">=\",\"7\"]", "[\">=\",null]", "[\">=\",true]",
                "[\">\",0,\">\",1]", "[\">=\",0,\">\",1]",
                "[\">\",0,\">=\",1]", "[\">=\",0,\">=\",1]",
                "[\"<\",9,\"<\",7]", "[\"<=\",9,\"<=\",7]",
                "[\"=\",7,\">\",6]", "[\">\",6,\"=\",7]",
                "[\"=\",7,\"=\",7]", "[\"<\",9,\">\",7]",
                "[\"<=\",9,\">=\",7]", "[\">\",9,\"<\",7]",
                "[\">=\",9,\"<=\",7]", "[\">\",7,\"<\",7]",
                "[\">\",7,\"<=\",7]", "[\">=\",7,\"<\",7]",
                "[\">=\",7,\"<=\",7]", "[\">\",0,\"<\",9,\"<\",8]")
                .map(filter -> "{\"detail\":{\"severity\":[{\"numeric\":" + filter + "}]}}");
        return Stream.concat(invalidFilters, Stream.of(
                "{\"detail\":{\"nested\":{\"severity\":[{\"numeric\":[]}]}}}",
                "{\"$or\":[{\"source\":[\"example.test\"]},{\"detail\":{\"severity\":[{\"numeric\":[]}]}}]}",
                "{\"detail\":{\"$or\":[{\"severity\":[7]},{\"severity\":[{\"numeric\":[]}] }]}}",
                "{\"detail\":{\"severity\":[7,{\"numeric\":[]}]}}"));
    }
}
