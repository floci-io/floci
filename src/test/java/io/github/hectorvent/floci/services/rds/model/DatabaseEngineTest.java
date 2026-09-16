package io.github.hectorvent.floci.services.rds.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseEngineTest {

    @Test
    void sqlServerUsesRdsPortAndMasterUsernameLimit() {
        assertEquals(1433, DatabaseEngine.SQLSERVER.defaultPort());
        assertEquals(16, DatabaseEngine.SQLSERVER.maxMasterUsernameLength());
    }
}
