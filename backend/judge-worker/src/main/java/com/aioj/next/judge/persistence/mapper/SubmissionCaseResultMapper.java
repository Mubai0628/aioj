package com.aioj.next.judge.persistence.mapper;

import com.aioj.next.judge.persistence.entity.SubmissionCaseResultEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SubmissionCaseResultMapper extends BaseMapper<SubmissionCaseResultEntity> {
    /**
     * Writes all case results of one submission in a single statement; every
     * submission now records its executed cases, so per-row inserts add up.
     * Callers assign ids.
     */
    @Insert("""
            <script>
            INSERT INTO submission_case_results (
                id, submission_id, contest_id, contest_problem_id, contest_participant_id, testcase_package_id,
                case_id, case_index, case_name, subtask_key, status, judge_phase, sandbox_status, sample,
                score, max_score, time_millis, memory_kb, message, created_at
            ) VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.id}, #{r.submissionId}, #{r.contestId}, #{r.contestProblemId}, #{r.contestParticipantId},
                 #{r.testcasePackageId}, #{r.caseId}, #{r.caseIndex}, #{r.caseName}, #{r.subtaskKey}, #{r.status},
                 #{r.judgePhase}, #{r.sandboxStatus}, #{r.sample}, #{r.score}, #{r.maxScore}, #{r.timeMillis},
                 #{r.memoryKb}, #{r.message}, #{r.createdAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<SubmissionCaseResultEntity> rows);
}
