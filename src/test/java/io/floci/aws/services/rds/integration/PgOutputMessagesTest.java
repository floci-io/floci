package io.floci.aws.services.rds.integration;

import io.floci.aws.services.rds.integration.PgOutputMessages.Commit;
import io.floci.aws.services.rds.integration.PgOutputMessages.Delete;
import io.floci.aws.services.rds.integration.PgOutputMessages.Insert;
import io.floci.aws.services.rds.integration.PgOutputMessages.Other;
import io.floci.aws.services.rds.integration.PgOutputMessages.Relation;
import io.floci.aws.services.rds.integration.PgOutputMessages.Truncate;
import io.floci.aws.services.rds.integration.PgOutputMessages.Update;
import io.floci.aws.services.rds.integration.PgOutputMessages.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Decoding the pgoutput messages a replication slot gives, built here byte for byte as protocol version 1 has them. */
class PgOutputMessagesTest {

    @Test
    void decodesARelationWithItsSchemaTableAndColumns() {
        byte[] data = new PgOutputBytes().type('R').int32(16385).string("sales").string("orders").byte1('d')
                .int16(2)
                .byte1(1).string("id").int32(23).int32(-1)
                .byte1(0).string("note").int32(25).int32(-1)
                .build();

        Relation relation = (Relation) PgOutputMessages.decode(data);

        assertEquals(16385, relation.relationId());
        assertEquals("sales", relation.schema());
        assertEquals("orders", relation.table());
        assertThat(relation.columns(), contains("id", "note"));
    }

    @Test
    void decodesAnInsertWithTextAndNullValues() {
        byte[] data = new PgOutputBytes().type('I').int32(16385).byte1('N').int16(2).text("7").byte1('n').build();

        Insert insert = (Insert) PgOutputMessages.decode(data);

        assertEquals(16385, insert.relationId());
        assertThat(insert.row().values(), contains(new Value(Value.Kind.TEXT, "7"), Value.NULL));
    }

    @Test
    void decodesAnUpdateWithItsOldKeyAndAnUnchangedToastedValue() {
        byte[] data = new PgOutputBytes().type('U').int32(1).byte1('K').int16(2).text("1").byte1('n')
                .byte1('N').int16(2).text("2").byte1('u').build();

        Update update = (Update) PgOutputMessages.decode(data);

        assertThat(update.oldRow().values(), contains(new Value(Value.Kind.TEXT, "1"), Value.NULL));
        assertThat(update.row().values(), contains(new Value(Value.Kind.TEXT, "2"), Value.UNCHANGED));
    }

    @Test
    void decodesAnUpdateThatKeepsItsKey() {
        byte[] data = new PgOutputBytes().type('U').int32(1).byte1('N').int16(1).text("ünïcode").build();

        Update update = (Update) PgOutputMessages.decode(data);

        assertNull(update.oldRow());
        assertThat(update.row().values(), contains(new Value(Value.Kind.TEXT, "ünïcode")));
    }

    @Test
    void decodesADeleteByItsKey() {
        byte[] data = new PgOutputBytes().type('D').int32(1).byte1('K').int16(1).text("9").build();

        Delete delete = (Delete) PgOutputMessages.decode(data);

        assertThat(delete.oldRow().values(), contains(new Value(Value.Kind.TEXT, "9")));
    }

    @Test
    void decodesATruncateOfSeveralTables() {
        byte[] data = new PgOutputBytes().type('T').int32(2).byte1(0).int32(10).int32(11).build();

        Truncate truncate = (Truncate) PgOutputMessages.decode(data);

        assertEquals(List.of(10, 11), truncate.relationIds());
    }

    @Test
    void readsTheEndOfACommittedTransactionAsAnLsn() {
        byte[] data = new PgOutputBytes().type('C').byte1(0).int64(0x16B3748L).int64(0x1_016B_3780L).int64(0)
                .build();

        Commit commit = (Commit) PgOutputMessages.decode(data);

        assertEquals("1/16B3780", commit.endLsn());
    }

    @Test
    void readsAMessageItHasNoUseForAsOther() {
        byte[] data = new PgOutputBytes().type('Y').int32(600).string("public").string("mood").build();

        assertThat(PgOutputMessages.decode(data), instanceOf(Other.class));
    }
}
