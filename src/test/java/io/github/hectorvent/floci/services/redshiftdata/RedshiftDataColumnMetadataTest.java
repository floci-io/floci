package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.h2.Driver;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftDataColumnMetadataTest {

    static {
        Driver.load();
    }

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void emitsEveryColumnMetadataFieldForEachColumn() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("create table t (id int not null, name varchar(50))");
            try (ResultSet rs = st.executeQuery("select id as id, name as name from t")) {
                ArrayNode columns = RedshiftDataColumnMetadata.toColumnMetadata(om, rs.getMetaData());
                assertEquals(2, columns.size());
                var id = columns.get(0);
                assertEquals("ID", id.get("name").asText().toUpperCase());
                assertTrue(id.has("label"));
                assertTrue(id.has("typeName"));
                assertTrue(id.has("nullable"));
                assertTrue(id.has("length"));
                assertTrue(id.has("precision"));
                assertTrue(id.has("scale"));
                assertTrue(id.has("isCaseSensitive"));
                assertTrue(id.has("isCurrency"));
                assertTrue(id.has("isSigned"));
                assertTrue(id.has("schemaName"));
                assertTrue(id.has("tableName"));
                assertTrue(id.has("columnDefault"));
            }
        }
    }
}
