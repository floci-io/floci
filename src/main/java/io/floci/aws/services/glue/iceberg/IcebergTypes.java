package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.StringJoiner;

/**
 * The Hive type a Glue column holds for an Iceberg field, as AWS Glue writes an Iceberg table's columns: a primitive
 * as its Hive name ({@code long} is {@code bigint}), a nested type spelled out ({@code struct<a:int>},
 * {@code array<string>}, {@code map<string,bigint>}).
 */
final class IcebergTypes {

    private IcebergTypes() {
    }

    static String hiveType(JsonNode type) {
        if (type.isTextual()) {
            return primitive(type.asText());
        }
        return switch (type.path("type").asText()) {
            case "struct" -> {
                StringJoiner fields = new StringJoiner(",", "struct<", ">");
                for (JsonNode field : type.path("fields")) {
                    fields.add(field.path("name").asText() + ":" + hiveType(field.path("type")));
                }
                yield fields.toString();
            }
            case "list" -> "array<" + hiveType(type.path("element")) + ">";
            case "map" -> "map<" + hiveType(type.path("key")) + "," + hiveType(type.path("value")) + ">";
            default -> "string";
        };
    }

    private static String primitive(String type) {
        if (type.startsWith("decimal")) {
            return type.replace(" ", "");
        }
        if (type.startsWith("fixed")) {
            return "binary";
        }
        return switch (type) {
            case "boolean" -> "boolean";
            case "int" -> "int";
            case "long" -> "bigint";
            case "float" -> "float";
            case "double" -> "double";
            case "date" -> "date";
            case "timestamp", "timestamptz", "timestamp_ns", "timestamptz_ns" -> "timestamp";
            case "binary" -> "binary";
            default -> "string";
        };
    }
}
