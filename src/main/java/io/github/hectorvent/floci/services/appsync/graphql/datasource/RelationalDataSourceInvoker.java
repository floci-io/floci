package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.rdsdata.RdsDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code RELATIONAL_DATABASE} data source: runs the resolver's statements over the RDS Data API
 * against the cluster in {@code relationalDatabaseConfig}.
 *
 * <p>The result is wrapped as {@code {"sqlStatementResults": [ … ]}}, one entry per statement, which
 * is the shape {@code toJsonObject()} from {@code @aws-appsync/utils/rds} expects: the call every
 * relational JS resolver's {@code response()} handler ends with.
 *
 * <p>Three request shapes are accepted, because AppSync itself moved between them: the classic
 * {@code {statements, variableMap}}, the {@code {statement, parameters}} that the {@code sql},
 * {@code select}, {@code insert}, {@code update} and {@code remove} helpers build, and a bare SQL
 * string. A list of the second form (what {@code createPgStatement} returns) works too.
 */
@ApplicationScoped
public class RelationalDataSourceInvoker implements AppSyncDataSourceInvoker {

    private final RdsDataService rdsDataService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public RelationalDataSourceInvoker(RdsDataService rdsDataService,
                                       RegionResolver regionResolver,
                                       ObjectMapper objectMapper) {
        this.rdsDataService = rdsDataService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.RELATIONAL_DATABASE;
    }

    @Override
    public Object invoke(DataSource dataSource, Object request, String region) {
        Map<String, Object> endpoint = rdsEndpointConfig(dataSource);
        String resourceArn = clusterArn(endpoint, dataSource, region);
        String secretArn = text(endpoint.get("awsSecretStoreArn"));
        String database = text(endpoint.get("databaseName"));
        if (secretArn == null || database == null) {
            throw new AwsException("InternalFailureException",
                    "Data source " + dataSource.getName()
                            + " is missing rdsHttpEndpointConfig.awsSecretStoreArn or databaseName", 500);
        }

        List<Statement> statements = statements(request);
        if (statements.isEmpty()) {
            throw new AwsException("InternalFailureException",
                    "The resolver produced no SQL statement for data source " + dataSource.getName(), 500);
        }

        ArrayNode results = objectMapper.createArrayNode();
        for (Statement statement : statements) {
            ObjectNode rdsRequest = objectMapper.createObjectNode();
            rdsRequest.put("resourceArn", resourceArn);
            rdsRequest.put("secretArn", secretArn);
            rdsRequest.put("database", database);
            rdsRequest.put("sql", statement.sql());
            // Column metadata is what turns records into keyed rows; toJsonObject cannot name a
            // column without it.
            rdsRequest.put("includeResultMetadata", true);
            if (!statement.parameters().isEmpty()) {
                rdsRequest.set("parameters", parameterNodes(statement.parameters()));
            }
            ObjectNode response = rdsDataService.executeStatement(rdsRequest, region);
            results.add(sqlStatementResult(response));
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("sqlStatementResults", results);
        return objectMapper.convertValue(wrapper, Object.class);
    }

    /** One statement's SQL plus its parameters, named {@code param1..paramN}. */
    private record Statement(String sql, List<Parameter> parameters) {}

    /**
     * A bound value and the type the resolver declared for it.
     *
     * <p>The hint is load-bearing rather than decorative. A resolver's date filter binds a string
     * and declares it {@code TIMESTAMP}; without the hint the Data API binds it as text and
     * PostgreSQL refuses the comparison outright: {@code operator does not exist: timestamp with
     * time zone >= character varying}, so every filtered query fails.
     */
    private record Parameter(String name, Object value, String typeHint) {}

    private List<Statement> statements(Object request) {
        List<Statement> statements = new ArrayList<>();
        if (request instanceof String sql) {
            statements.add(new Statement(sql, List.of()));
            return statements;
        }
        if (request instanceof List<?> list) {
            for (Object element : list) {
                statements.addAll(statements(element));
            }
            return statements;
        }
        if (!(request instanceof Map<?, ?> map)) {
            return statements;
        }
        // Classic AppSync: a list of statements sharing one variableMap.
        if (map.get("statements") instanceof List<?> list) {
            List<Parameter> shared = variableMapParameters(map.get("variableMap"),
                    map.get("variableTypeHintMap"));
            for (Object element : list) {
                statements.add(new Statement(String.valueOf(element), shared));
            }
            return statements;
        }
        Object statement = map.get("statement");
        if (statement != null) {
            statements.add(new Statement(String.valueOf(statement),
                    positionalParameters(map.get("parameters"), map.get("variableMap"),
                            map.get("variableTypeHintMap"))));
        }
        return statements;
    }

    /**
     * {@code variableMap} names its own placeholders ({@code {":id": 7}}); the Data API takes the
     * same names without the colon.
     */
    private List<Parameter> variableMapParameters(Object variableMap, Object typeHints) {
        List<Parameter> parameters = new ArrayList<>();
        if (variableMap instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String placeholder = String.valueOf(key);
                String name = placeholder.startsWith(":") ? placeholder.substring(1) : placeholder;
                parameters.add(new Parameter(name, value, typeHint(typeHints, placeholder, name)));
            });
        }
        return parameters;
    }

    /**
     * The hint declared for a placeholder. Looked up under both spellings because
     * {@code variableTypeHintMap} is keyed by the placeholder as written in the statement
     * ({@code :v0}), while the Data API's parameter name drops the colon.
     */
    private String typeHint(Object typeHints, String placeholder, String name) {
        if (!(typeHints instanceof Map<?, ?> map)) {
            return null;
        }
        Object hint = map.get(placeholder);
        if (hint == null) {
            hint = map.get(name);
        }
        return hint == null || String.valueOf(hint).isBlank() ? null : String.valueOf(hint);
    }

    /**
     * The helpers emit positional parameters and reference them as {@code :param1..:paramN}, so the
     * index is the name. A {@code variableMap} alongside them is honoured too, for a resolver that
     * hand-rolls its statement.
     */
    private List<Parameter> positionalParameters(Object parameters, Object variableMap, Object typeHints) {
        List<Parameter> named = new ArrayList<>(variableMapParameters(variableMap, typeHints));
        if (parameters instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String name = "param" + (i + 1);
                named.add(new Parameter(name, list.get(i), typeHint(typeHints, ":" + name, name)));
            }
        }
        return named;
    }

    private ArrayNode parameterNodes(List<Parameter> parameters) {
        ArrayNode nodes = objectMapper.createArrayNode();
        for (Parameter parameter : parameters) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", parameter.name());
            node.set("value", parameterValue(parameter.value()));
            if (parameter.typeHint() != null) {
                node.put("typeHint", parameter.typeHint());
            }
            nodes.add(node);
        }
        return nodes;
    }

    private ObjectNode parameterValue(Object value) {
        ObjectNode node = objectMapper.createObjectNode();
        if (value == null) {
            node.put("isNull", true);
        } else if (value instanceof Boolean bool) {
            node.put("booleanValue", bool);
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            node.put("longValue", ((Number) value).longValue());
        } else if (value instanceof Number number) {
            node.put("doubleValue", number.doubleValue());
        } else if (value instanceof Map || value instanceof List) {
            // A structured parameter is bound as its JSON text, which is what a json/jsonb column
            // takes; Postgres casts it on the way in.
            node.put("stringValue", writeJson(value));
        } else {
            node.put("stringValue", String.valueOf(value));
        }
        return node;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AwsException("InternalFailureException",
                    "Could not serialise a SQL parameter: " + e.getMessage(), 500);
        }
    }

    /** Carries a statement's records, metadata and update count into the wrapper. */
    private ObjectNode sqlStatementResult(ObjectNode response) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("numberOfRecordsUpdated", response.path("numberOfRecordsUpdated").asLong(0));
        JsonNode records = response.get("records");
        result.set("records", records == null || records.isNull()
                ? objectMapper.createArrayNode() : records);
        JsonNode metadata = response.get("columnMetadata");
        if (metadata != null && !metadata.isNull()) {
            result.set("columnMetadata", metadata);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rdsEndpointConfig(DataSource dataSource) {
        Map<String, Object> config = dataSource.getRelationalDatabaseConfig();
        Object endpoint = config == null ? null : config.get("rdsHttpEndpointConfig");
        if (!(endpoint instanceof Map<?, ?> map)) {
            throw new AwsException("InternalFailureException",
                    "Data source " + dataSource.getName()
                            + " has no relationalDatabaseConfig.rdsHttpEndpointConfig", 500);
        }
        return (Map<String, Object>) map;
    }

    /**
     * The Data API addresses a cluster by ARN, while the data source records the identifier a
     * CloudFormation template gives it. An identifier that is already an ARN is used as-is.
     */
    private String clusterArn(Map<String, Object> endpoint, DataSource dataSource, String region) {
        String identifier = text(endpoint.get("dbClusterIdentifier"));
        if (identifier == null) {
            throw new AwsException("InternalFailureException",
                    "Data source " + dataSource.getName()
                            + " has no rdsHttpEndpointConfig.dbClusterIdentifier", 500);
        }
        if (identifier.startsWith("arn:")) {
            return identifier;
        }
        String endpointRegion = text(endpoint.get("awsRegion"));
        return AwsArnUtils.Arn.of("rds", endpointRegion == null ? region : endpointRegion,
                regionResolver.getAccountId(), "cluster:" + identifier).toString();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
