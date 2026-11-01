package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RedshiftDataParameters {

    private String database;
    private String dbUser;
    private String secretManagerArn;
    private String sql;
    private List<String> sqls;
    private String statementName;
    private Boolean withEvent;

    @JsonProperty("Database")
    public String getDatabase() { return database; }
    @JsonProperty("Database")
    public void setDatabase(String database) { this.database = database; }

    @JsonProperty("DbUser")
    public String getDbUser() { return dbUser; }
    @JsonProperty("DbUser")
    public void setDbUser(String dbUser) { this.dbUser = dbUser; }

    @JsonProperty("SecretManagerArn")
    public String getSecretManagerArn() { return secretManagerArn; }
    @JsonProperty("SecretManagerArn")
    public void setSecretManagerArn(String secretManagerArn) { this.secretManagerArn = secretManagerArn; }

    @JsonProperty("Sql")
    public String getSql() { return sql; }
    @JsonProperty("Sql")
    public void setSql(String sql) { this.sql = sql; }

    @JsonProperty("Sqls")
    public List<String> getSqls() { return sqls; }
    @JsonProperty("Sqls")
    public void setSqls(List<String> sqls) { this.sqls = sqls; }

    @JsonProperty("StatementName")
    public String getStatementName() { return statementName; }
    @JsonProperty("StatementName")
    public void setStatementName(String statementName) { this.statementName = statementName; }

    @JsonProperty("WithEvent")
    public Boolean getWithEvent() { return withEvent; }
    @JsonProperty("WithEvent")
    public void setWithEvent(Boolean withEvent) { this.withEvent = withEvent; }
}
