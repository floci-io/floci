package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The endpoints of a DynamoDB zero-ETL integration in the form AWS takes them: a DynamoDB table as
 * the source and a Redshift Serverless namespace as the target.
 */
@ApplicationScoped
public class RedshiftZeroEtlEndpoints {

    private final DynamoDbFacade dynamoDb;
    private final RedshiftServerlessService serverless;
    private final S3Service s3;
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftZeroEtlEndpoints(DynamoDbFacade dynamoDb, RedshiftServerlessService serverless, S3Service s3,
                                    RegionResolver regionResolver) {
        this.dynamoDb = dynamoDb;
        this.serverless = serverless;
        this.s3 = s3;
        this.regionResolver = regionResolver;
    }

    /**
     * The Region of the bucket an S3 event integration takes as its source, read as {@code accountId}. A bucket
     * that does not exist is an integration source that cannot be found.
     */
    public String sourceBucketRegion(String accountId, String region, String bucketName) {
        try {
            return RequestScopes.callAs(accountId, region, () -> {
                if (!s3.ownsBucket(bucketName)) {
                    throw sourceNotFound();
                }
                String bucketRegion = s3.getBucketRegion(bucketName);
                if (bucketRegion == null || bucketRegion.isBlank()) {
                    return regionResolver.getDefaultRegion();
                }
                return bucketRegion;
            });
        } catch (AwsException e) {
            if ("NoSuchBucket".equals(e.getErrorCode())) {
                throw sourceNotFound();
            }
            throw e;
        }
    }

    /**
     * The table a table ARN names, read as {@code accountId} in the ARN's Region. The table must have
     * exactly that ARN, so a table of another account, or an ARN with more after the table name, is
     * not mistaken for the caller's table of the same name.
     */
    public TableDefinition sourceTable(String accountId, String tableArn) {
        String region = AwsArnUtils.parse(tableArn).region();
        TableDefinition table;
        try {
            table = RequestScopes.callAs(accountId, region, () ->
                    dynamoDb.tables().describeTable(new Scope(accountId, region), tableName(tableArn)));
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw sourceNotFound();
            }
            throw e;
        }
        if (!tableArn.equals(table.getTableArn())) {
            throw sourceNotFound();
        }
        return table;
    }

    private static AwsException sourceNotFound() {
        return new AwsException("IntegrationSourceNotFoundFault",
                "The specified integration source can't be found.", 404);
    }

    /** Refuses a namespace ARN that names no namespace of the caller. */
    public void requireTargetNamespace(String namespaceArn) {
        if (serverless.findNamespaceByArn(namespaceArn, AwsArnUtils.parse(namespaceArn).region()).isEmpty()) {
            throw new AwsException("IntegrationTargetNotFoundFault",
                    "The specified integration target can't be found.", 404);
        }
    }

    /** The workgroup that serves the namespace, as {@code accountId}. */
    public RedshiftServerlessService.WorkgroupTarget targetWorkgroup(String accountId, String namespaceArn) {
        String region = AwsArnUtils.parse(namespaceArn).region();
        return RequestScopes.callAs(accountId, region,
                () -> serverless.getNamespaceWorkgroupTarget(namespaceArn, region));
    }

    /** Whether a DynamoDB ARN names a stream ({@code table/<name>/stream/<label>}) rather than a table. */
    public static boolean isStreamArn(String dynamoDbArn) {
        return AwsArnUtils.parse(dynamoDbArn).resource().contains("/stream/");
    }

    /** Whether an ARN names a Redshift Serverless namespace. */
    public static boolean isNamespaceArn(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        return "redshift-serverless".equals(parsed.service()) && parsed.resource().startsWith("namespace/");
    }

    /** The table name in a table ARN or a stream ARN. */
    public static String tableName(String tableOrStreamArn) {
        String resource = AwsArnUtils.parse(tableOrStreamArn).resource();
        String name = resource.substring("table/".length());
        int stream = name.indexOf('/');
        return stream < 0 ? name : name.substring(0, stream);
    }
}
