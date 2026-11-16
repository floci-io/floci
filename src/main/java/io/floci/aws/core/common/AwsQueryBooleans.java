package io.floci.aws.core.common;

/** Parses a supplied Query boolean; callers decide how to handle an absent member. */
public final class AwsQueryBooleans {

    private AwsQueryBooleans() {
    }

    public static boolean parse(String raw) {
        if (raw.isEmpty()) {
            throw new AwsException("MalformedInput", "missing value for boolean type", 400);
        }
        return switch (raw) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new AwsException("MalformedInput",
                    "boolean must follow xsd1.1 definition", 400);
        };
    }
}
