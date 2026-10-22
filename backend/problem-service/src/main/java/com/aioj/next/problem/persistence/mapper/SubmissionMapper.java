package com.aioj.next.problem.persistence.mapper;

import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SubmissionMapper extends BaseMapper<SubmissionEntity> {
    /** One row per problem the user practised, most recent first; contest submissions do not count. */
    @Select("""
            SELECT problem_id AS problemId,
                   COUNT(*) AS attempts,
                   SUM(CASE WHEN status IN ('WRONG_ANSWER', 'RUNTIME_ERROR', 'TIME_LIMIT_EXCEEDED',
                                            'MEMORY_LIMIT_EXCEEDED', 'OUTPUT_LIMIT_EXCEEDED') THEN 1 ELSE 0 END) AS failures,
                   MAX(CASE WHEN status = 'ACCEPTED' THEN 1 ELSE 0 END) AS accepted
            FROM submissions
            WHERE user_id = #{userId} AND contest_id IS NULL AND problem_id IS NOT NULL
            GROUP BY problem_id
            ORDER BY MAX(created_at) DESC
            LIMIT #{limit}
            """)
    List<PracticeProblemHistory> practiceHistory(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(DISTINCT problem_id) FROM submissions
            WHERE user_id = #{userId} AND contest_id IS NULL AND status = 'ACCEPTED'
            """)
    long countSolvedPracticeProblems(@Param("userId") Long userId);
}
