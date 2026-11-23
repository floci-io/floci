package io.floci.aws.core.common;

import java.util.Locale;

/** The boolean a JSON string names, as AWS's JSON protocol deserializers read one. */
public final class JsonBooleanCoercion {

    private JsonBooleanCoercion() {
    }

    /**
     * The boolean {@code value} names, in any case and untrimmed: {@code true}, {@code yes}, {@code y},
     * {@code t} or {@code 1}, and {@code false}, {@code no}, {@code n}, {@code f} or {@code 0}, as SES v2's
     * and KMS's deserializers read a string (measured on AWS). Any other string is refused with the
     * deserializer's {@code SerializationException}. Callers read the member's other JSON types themselves,
     * each service's deserializer answering them in its own way.
     */
    public static boolean fromString(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "t", "1" -> true;
            case "false", "no", "n", "f", "0" -> false;
            default -> throw new AwsException("SerializationException",
                    "STRING_VALUE can not be converted to an Boolean", 400);
        };
    }
}
