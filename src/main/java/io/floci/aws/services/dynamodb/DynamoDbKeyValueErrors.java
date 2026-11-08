package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;

final class DynamoDbKeyValueErrors {

    private DynamoDbKeyValueErrors() {
    }

    static AwsException emptyKeyValue(String type, String keyName) {
        return new AwsException("ValidationException",
                "One or more parameter values are not valid. "
                + "The AttributeValue for a key attribute cannot contain an "
                + emptyValueKind(type) + ". Key: " + keyName, 400);
    }

    static AwsException emptyIndexKeyValue(String type, String indexName, String attributeName) {
        return new AwsException("ValidationException",
                "One or more parameter values are not valid. A value specified for a secondary "
                + "index key is not supported. The AttributeValue for a key attribute cannot "
                + "contain an " + emptyValueKind(type) + ". IndexName: " + indexName
                + ", IndexKey: " + attributeName, 400);
    }

    static KeySchemaMismatchException indexKeyTypeMismatch(String attributeName, String expectedType,
                                                           String actualType, String indexName) {
        return new KeySchemaMismatchException(
                "One or more parameter values were invalid: Type mismatch for Index Key " + attributeName
                + " Expected: " + expectedType + " Actual: " + actualType + " IndexName: " + indexName);
    }

    static String emptyValueKind(String type) {
        return "B".equals(type) ? "empty binary value" : "empty string value";
    }
}
