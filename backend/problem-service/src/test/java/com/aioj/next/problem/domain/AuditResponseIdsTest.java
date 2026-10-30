package com.aioj.next.problem.domain;

import com.aioj.next.contract.contest.FairnessAlertResponse;
import com.aioj.next.contract.operation.OperationAuditEventResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan S6: audit responses carry their snowflake ids as strings; a browser would round a 19-digit number. */
class AuditResponseIdsTest {
    @Test
    void auditResponsesHaveNoNumericIdFields() {
        for (Class<?> type : List.of(OperationAuditEventResponse.class, FairnessAlertResponse.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName();
                if (name.equals("id") || name.endsWith("Id") || name.endsWith("By")) {
                    assertThat(component.getType()).as(type.getSimpleName() + "." + name).isEqualTo(String.class);
                }
            }
        }
    }
}
