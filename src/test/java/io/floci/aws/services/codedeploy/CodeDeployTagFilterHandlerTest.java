package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeDeployTagFilterHandlerTest {

    @Test
    void optionalTagFilterMembersRemainAbsent() throws Exception {
        CodeDeployService service = mock(CodeDeployService.class);
        when(service.listOnPremisesInstances(eq("us-east-1"), isNull(), anyList())).thenReturn(List.of());
        ObjectMapper mapper = new ObjectMapper();
        CodeDeployJsonHandler handler = new CodeDeployJsonHandler(service, mapper);

        handler.handle("ListOnPremisesInstances", mapper.readTree("""
                {"tagFilters":[{"Key":"team"},{"Value":"red"},{"Type":"KEY_ONLY"}]}
                """), "us-east-1");

        verify(service).listOnPremisesInstances(eq("us-east-1"), isNull(),
                eq(List.of(Map.of("Key", "team"), Map.of("Value", "red"), Map.of("Type", "KEY_ONLY"))));
    }

    @Test
    void explicitNullTagFiltersAreTreatedAsAbsent() throws Exception {
        CodeDeployService service = mock(CodeDeployService.class);
        when(service.listOnPremisesInstances(eq("us-east-1"), isNull(), anyList())).thenReturn(List.of());
        ObjectMapper mapper = new ObjectMapper();
        CodeDeployJsonHandler handler = new CodeDeployJsonHandler(service, mapper);

        handler.handle("ListOnPremisesInstances", mapper.readTree("{\"tagFilters\":null}"), "us-east-1");

        verify(service).listOnPremisesInstances(eq("us-east-1"), isNull(), eq(List.of()));
    }

    @Test
    void nullTagFilterMembersAreTreatedAsAbsent() throws Exception {
        CodeDeployService service = mock(CodeDeployService.class);
        when(service.listOnPremisesInstances(eq("us-east-1"), isNull(), anyList())).thenReturn(List.of());
        ObjectMapper mapper = new ObjectMapper();
        CodeDeployJsonHandler handler = new CodeDeployJsonHandler(service, mapper);

        handler.handle("ListOnPremisesInstances", mapper.readTree("""
                {"tagFilters":[{"Key":null,"Value":null,"Type":null}]}
                """), "us-east-1");

        verify(service).listOnPremisesInstances(eq("us-east-1"), isNull(), eq(List.of(Map.of())));
    }
}
