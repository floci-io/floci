package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One DB cluster of an Aurora global database: the primary (writer) or a secondary. */
@RegisterForReflection
public class GlobalClusterMember {

    private String dbClusterArn;
    private boolean writer;

    public GlobalClusterMember() {}

    public GlobalClusterMember(String dbClusterArn, boolean writer) {
        this.dbClusterArn = dbClusterArn;
        this.writer = writer;
    }

    public String getDbClusterArn() { return dbClusterArn; }
    public void setDbClusterArn(String dbClusterArn) { this.dbClusterArn = dbClusterArn; }

    public boolean isWriter() { return writer; }
    public void setWriter(boolean writer) { this.writer = writer; }
}
