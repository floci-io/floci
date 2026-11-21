package io.floci.aws.services.cloudformation.provisioners;

import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.model.TableDefinition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/** The {@code createTable} call the provisioner makes for a {@code PAY_PER_REQUEST} table. */
public final class DynamoDbCreateCalls {

    private DynamoDbCreateCalls() {
    }

    /**
     * Any table in any region. Pass the mock to stub it with {@code when(...)}, or
     * {@code verify(mock, ...)} to check it.
     */
    public static TableDefinition onDemandCreate(DynamoDbService dynamoDb) {
        return dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(),
                anyList(), eq("PAY_PER_REQUEST"), anyString());
    }

    /** The named table in the given region. */
    public static TableDefinition onDemandCreate(DynamoDbService dynamoDb, String tableName, String region) {
        return dynamoDb.createTable(eq(tableName), anyList(), anyList(), any(), any(), anyList(), anyList(),
                anyList(), eq("PAY_PER_REQUEST"), eq(region));
    }
}
