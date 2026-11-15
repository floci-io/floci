package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.GsiThroughputUpdate;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.Throughput;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.ProvisionedThroughput;
import io.floci.aws.services.dynamodb.model.TableDefinition;

import java.util.List;

/**
 * The provisioned capacity quotas DescribeLimits reports, which CreateTable and UpdateTable
 * enforce. On-demand tables are outside them.
 */
final class DynamoDbCapacityQuotas {

    // The default account quotas, the same in every Region measured.
    static final long ACCOUNT_MAX_CAPACITY_UNITS = 80_000;
    static final long TABLE_MAX_CAPACITY_UNITS = 40_000;

    private static final String READ = "ReadCapacityUnits";
    private static final String WRITE = "WriteCapacityUnits";
    private static final String SEE_GUIDE =
            " Refer to the Amazon DynamoDB Developer Guide for current limits and how to request higher limits.";

    private DynamoDbCapacityQuotas() {
    }

    /** Capacity units summed over a table and its global secondary indexes. */
    record Capacity(long read, long write) {

        static final Capacity NONE = new Capacity(0, 0);

        static Capacity of(TableDefinition table) {
            if ("PAY_PER_REQUEST".equals(table.getBillingMode())) {
                return NONE;
            }
            ProvisionedThroughput throughput = table.getProvisionedThroughput();
            Capacity total = new Capacity(throughput.getReadCapacityUnits(), throughput.getWriteCapacityUnits());
            for (GlobalSecondaryIndex gsi : table.getGlobalSecondaryIndexes()) {
                total = total.plus(of(gsi.getProvisionedThroughput()));
            }
            return total;
        }

        private static Capacity of(ProvisionedThroughput throughput) {
            return new Capacity(throughput.getReadCapacityUnits(), throughput.getWriteCapacityUnits());
        }

        Capacity plus(Capacity other) {
            return new Capacity(read + other.read, write + other.write);
        }
    }

    /**
     * Checks a provisioned table before CreateTable stores it.
     *
     * @param reserved what the account's other tables in the Region hold
     */
    static void requireNewTableWithinQuotas(TableDefinition table, Capacity reserved, String region) {
        Capacity requested = Capacity.of(table);
        requireWithinAccountQuota(READ, reserved.read(), null, requested.read(), region);
        requireWithinAccountQuota(WRITE, reserved.write(), null, requested.write(), region);
        ProvisionedThroughput throughput = table.getProvisionedThroughput();
        requireWithinTableQuota(throughput.getReadCapacityUnits(), throughput.getWriteCapacityUnits(), region);
        for (GlobalSecondaryIndex gsi : table.getGlobalSecondaryIndexes()) {
            requireWithinIndexQuota(gsi.getIndexName(), gsi.getProvisionedThroughput(), region);
        }
    }

    /**
     * Checks the indexes an UpdateTable creates and the table's new total against the account
     * quota. The total is checked only when it grows.
     *
     * @param reserved what the account's tables in the Region hold, this table included
     */
    static void requireUpdateWithinQuotas(TableDefinition table, Long read, Long write,
                                          List<GlobalSecondaryIndex> creates, List<String> deletes,
                                          List<GsiThroughputUpdate> updates, Capacity reserved, String region) {
        Capacity current = Capacity.of(table);
        ProvisionedThroughput tableThroughput = table.getProvisionedThroughput();
        Capacity requested = new Capacity(read != null ? read : tableThroughput.getReadCapacityUnits(),
                write != null ? write : tableThroughput.getWriteCapacityUnits());
        for (GlobalSecondaryIndex gsi : table.getGlobalSecondaryIndexes()) {
            if (!deletes.contains(gsi.getIndexName())) {
                requested = requested.plus(updated(gsi, updates));
            }
        }
        for (GlobalSecondaryIndex gsi : creates) {
            requested = requested.plus(Capacity.of(gsi.getProvisionedThroughput()));
        }
        if (requested.read() > current.read()) {
            requireWithinAccountQuota(READ, reserved.read(), current.read(), requested.read(), region);
        }
        if (requested.write() > current.write()) {
            requireWithinAccountQuota(WRITE, reserved.write(), current.write(), requested.write(), region);
        }
        for (GlobalSecondaryIndex gsi : creates) {
            requireWithinIndexQuota(gsi.getIndexName(), gsi.getProvisionedThroughput(), region);
        }
    }

    private static Capacity updated(GlobalSecondaryIndex gsi, List<GsiThroughputUpdate> updates) {
        ProvisionedThroughput throughput = gsi.getProvisionedThroughput();
        long read = throughput.getReadCapacityUnits();
        long write = throughput.getWriteCapacityUnits();
        for (GsiThroughputUpdate update : updates) {
            Throughput requested = update.provisionedThroughput();
            if (requested != null && gsi.getIndexName().equals(update.indexName())) {
                read = requested.readCapacityUnits() != null ? requested.readCapacityUnits() : read;
                write = requested.writeCapacityUnits() != null ? requested.writeCapacityUnits() : write;
            }
        }
        return new Capacity(read, write);
    }

    static void requireWithinTableQuota(Long read, Long write, String region) {
        requireWithinPerTableMaximum(READ, "table", read, region);
        requireWithinPerTableMaximum(WRITE, "table", write, region);
    }

    static void requireWithinIndexQuota(String indexName, Long read, Long write, String region) {
        requireWithinPerTableMaximum(READ + " for index " + indexName, "index", read, region);
        requireWithinPerTableMaximum(WRITE + " for index " + indexName, "index", write, region);
    }

    private static void requireWithinIndexQuota(String indexName, ProvisionedThroughput throughput, String region) {
        requireWithinIndexQuota(indexName, throughput.getReadCapacityUnits(), throughput.getWriteCapacityUnits(),
                region);
    }

    // The message names the per table maximum for an index too.
    private static void requireWithinPerTableMaximum(String requested, String scope, Long units, String region) {
        if (units != null && units > TABLE_MAX_CAPACITY_UNITS) {
            throw new AwsException("LimitExceededException",
                    "The requested " + requested + ", " + units + ", is above the per " + scope
                    + " maximum for the account in " + region + ". Per table maximum: "
                    + TABLE_MAX_CAPACITY_UNITS + "." + SEE_GUIDE, 400);
        }
    }

    /** @param current the table's own total on UpdateTable, null on CreateTable */
    private static void requireWithinAccountQuota(String unit, long reserved, Long current, long requested,
                                                  String region) {
        long others = current != null ? reserved - current : reserved;
        if (others + requested <= ACCOUNT_MAX_CAPACITY_UNITS) {
            return;
        }
        String currentForTable = current != null
                ? " Current " + unit + " provisioned for the table and indexes: " + current + "."
                : "";
        throw new AwsException("LimitExceededException",
                "This request would have caused the " + unit + " limit to be exceeded for the account in "
                + region + ". Current " + unit + " reserved by the account: " + reserved + ". Limit: "
                + ACCOUNT_MAX_CAPACITY_UNITS + "." + currentForTable + " Requested: " + requested + "."
                + SEE_GUIDE, 400);
    }
}
