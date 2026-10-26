package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsQueryListsTest {

    @Test
    void aMemberKeyIsGenericNamedByItsShapeOrBare() {
        Pattern keys = AwsQueryLists.memberKeys("IamRoles", "IamRoleArn");

        assertTrue(keys.matcher("IamRoles.member.1").matches());
        assertTrue(keys.matcher("IamRoles.IamRoleArn.2").matches());
        assertTrue(keys.matcher("IamRoles.10").matches());
        assertFalse(keys.matcher("IamRoles.Other.1").matches());
        assertFalse(keys.matcher("AddIamRoles.member.1").matches());
        assertFalse(keys.matcher("IamRolesExtra.1").matches());
        assertFalse(keys.matcher("IamRoles.member.1.Arn").matches());
        assertFalse(keys.matcher("IamRoles").matches());
    }

    @Test
    void aListWhoseShapeNamesNoMemberTakesOnlyTheGenericForm() {
        Pattern keys = AwsQueryLists.memberKeys("LogExports", null);

        assertTrue(keys.matcher("LogExports.member.1").matches());
        assertTrue(keys.matcher("LogExports.1").matches());
        assertFalse(keys.matcher("LogExports.LogExport.1").matches());
    }

    @Test
    void valuesAreEveryNonBlankMemberInNumericOrderPastAGap() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SubnetIds.SubnetIdentifier.10", "tenth");
        params.putSingle("SubnetIds.member.2", "second");
        params.putSingle("SubnetIds.5", " ");
        params.putSingle("SubnetIds.1", "first");
        params.putSingle("VpcSecurityGroupIds.member.3", "other list");

        assertEquals(List.of("first", "second", "tenth"),
                AwsQueryLists.values(params, "SubnetIds", "SubnetIdentifier"));
        assertEquals(List.of(), AwsQueryLists.values(params, "TagKeys", "TagKey"));
    }

    @Test
    void aMemberIndexIsTheNumberTheKeyEndsIn() {
        assertEquals(12, AwsQueryLists.memberIndex("Filters.Filter.3.Values.Value.12"));
        assertEquals(Integer.MAX_VALUE, AwsQueryLists.memberIndex("Name"));
        assertEquals(Integer.MAX_VALUE, AwsQueryLists.memberIndex("List.member."));
        assertEquals(Integer.MAX_VALUE, AwsQueryLists.memberIndex("List.member.99999999999"));
    }
}
