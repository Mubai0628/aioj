package com.aioj.next.problem.domain;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.FairnessAlertResponse;
import com.aioj.next.contract.contest.FairnessAlertSeverity;
import com.aioj.next.contract.contest.FairnessAlertStatus;
import com.aioj.next.contract.contest.FairnessAlertType;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestFairnessAlertEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.mapper.ContestFairnessAlertMapper;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismPairMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionRequestFingerprintMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Plan S6: fairness alerts go to the teacher's page with every snowflake id as a string. */
@ExtendWith(MockitoExtension.class)
class ContestFairnessAlertServiceTest {
    private static final long CONTEST = 1990011223344550001L;
    private static final long RUN = 1990011223344550002L;
    private static final long PRIMARY = 1990011223344550003L;
    private static final long SECONDARY = 1990011223344550004L;

    @Mock private ContestMapper contestMapper;
    @Mock private ContestRunMapper contestRunMapper;
    @Mock private ContestParticipantMapper participantMapper;
    @Mock private SubmissionMapper submissionMapper;
    @Mock private SubmissionRequestFingerprintMapper fingerprintMapper;
    @Mock private PlagiarismJobMapper jobMapper;
    @Mock private PlagiarismPairMapper pairMapper;
    @Mock private ContestFairnessAlertMapper alertMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void idsLeaveAsStringsEvenInsideTheEvidence() {
        authenticateAdmin();
        ContestEntity contest = new ContestEntity();
        contest.setId(CONTEST);
        when(contestMapper.selectById(CONTEST)).thenReturn(contest);
        ContestRunEntity run = new ContestRunEntity();
        run.setId(RUN);
        run.setContestId(CONTEST);
        when(contestRunMapper.selectById(RUN)).thenReturn(run);
        Page<ContestFairnessAlertEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(alert()));
        page.setTotal(1);
        when(alertMapper.selectPage(any(), any())).thenReturn(page);
        when(participantMapper.selectBatchIds(any())).thenReturn(List.of(participant(PRIMARY, "甲"), participant(SECONDARY, "乙")));
        ObjectMapper json = new ObjectMapper();
        ContestFairnessAlertService service = new ContestFairnessAlertService(contestMapper, contestRunMapper,
                participantMapper, submissionMapper, fingerprintMapper, jobMapper, pairMapper, alertMapper, json);

        var result = service.list(CONTEST, RUN, null, null, null, null, 1, 20);
        String body = json.writeValueAsString(result);

        FairnessAlertResponse item = result.records().get(0);
        assertThat(item.contestRunId()).isEqualTo(String.valueOf(RUN));
        assertThat(item.primaryParticipantId()).isEqualTo(String.valueOf(PRIMARY));
        assertThat(item.reviewedBy()).isEqualTo("1990011223344550009");
        assertThat(item.primaryDisplayName()).isEqualTo("甲");
        assertThat(item.evidence().get("participantIds"))
                .isEqualTo(List.of(String.valueOf(PRIMARY), String.valueOf(SECONDARY)));
        assertThat(item.evidence().get("participantCount")).isEqualTo(2);
        assertThat(body).doesNotContainPattern(Pattern.compile("[:\\[,]\\s*-?\\d{16,}"));
    }

    private static ContestFairnessAlertEntity alert() {
        ContestFairnessAlertEntity alert = new ContestFairnessAlertEntity();
        alert.setId(1990011223344550005L);
        alert.setContestId(CONTEST);
        alert.setContestRunId(RUN);
        alert.setAlertType(FairnessAlertType.SHARED_IP_CLUSTER);
        alert.setSeverity(FairnessAlertSeverity.MEDIUM);
        alert.setStatus(FairnessAlertStatus.REVIEWED);
        alert.setPrimaryParticipantId(PRIMARY);
        alert.setSecondaryParticipantId(SECONDARY);
        alert.setEvidenceJson("{\"participantIds\":[" + PRIMARY + "," + SECONDARY + "],\"participantCount\":2}");
        alert.setReviewedBy(1990011223344550009L);
        alert.setReviewedAt(Instant.parse("2026-10-30T08:00:00Z"));
        alert.setCreatedAt(Instant.parse("2026-10-30T07:00:00Z"));
        alert.setUpdatedAt(Instant.parse("2026-10-30T08:00:00Z"));
        return alert;
    }

    private static ContestParticipantEntity participant(long id, String name) {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(id);
        participant.setDisplayNameSnapshot(name);
        return participant;
    }

    private static void authenticateAdmin() {
        SecurityPrincipal principal = new SecurityPrincipal(1L, "admin", Set.of(Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
