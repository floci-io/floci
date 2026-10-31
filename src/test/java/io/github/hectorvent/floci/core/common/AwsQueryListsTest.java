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
    void membersCarryThePrefixTheirFieldsArriveUnderAndTheIndexAsSpelled() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Tags.member.7.Key", "team");
        params.putSingle("Tags.member.7.Value", "platform");
        params.putSingle("Tags.member.2.Key", "owner");
        params.putSingle("Audience.1", "");
        params.putSingle("Audience.member.3", "second");
        params.putSingle("OtherTags.member.1.Key", "another list");

        assertEquals(List.of(new AwsQueryLists.IndexedMember("Tags.member.2", "2"),
                        new AwsQueryLists.IndexedMember("Tags.member.7", "7")),
                AwsQueryLists.members(params, "Tags", null),
                "one member per index, in the order the indexes read");
        assertEquals(List.of(new AwsQueryLists.IndexedMember("Audience.1", "1"),
                        new AwsQueryLists.IndexedMember("Audience.member.3", "3")),
                AwsQueryLists.members(params, "Audience", null),
                "the bare numbered form is a member too, empty value and all");
        assertEquals(List.of(), AwsQueryLists.members(params, "Filter", null));
    }

    @Test
    void aMemberIsReadUnderTheNameItsShapeGivesItToo() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SubnetIds.SubnetIdentifier.4", "fourth");

        assertEquals(List.of(new AwsQueryLists.IndexedMember("SubnetIds.SubnetIdentifier.4", "4")),
                AwsQueryLists.members(params, "SubnetIds", "SubnetIdentifier"));
        assertEquals(List.of(), AwsQueryLists.members(params, "SubnetIds", null),
                "without the name, the generic and bare forms only");
    }

    @Test
    void aMemberIndexIsTheNumberTheKeyEndsIn() {
        assertEquals(12, AwsQueryLists.memberIndex("Filters.Filter.3.Values.Value.12"));
        assertEquals(Integer.MAX_VALUE, AwsQueryLists.memberIndex("Name"));
        assertEquals(Integer.MAX_VALUE, AwsQueryLists.memberIndex("List.member."));
        assertEquals(Integer.MAX_VALUE, AwsQueryLists.memberIndex("List.member.99999999999"));
    }
}
