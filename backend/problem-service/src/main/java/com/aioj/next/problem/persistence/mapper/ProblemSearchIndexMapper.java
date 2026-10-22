package com.aioj.next.problem.persistence.mapper;

import com.aioj.next.problem.persistence.entity.ProblemSearchIndexEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ProblemSearchIndexMapper extends BaseMapper<ProblemSearchIndexEntity> {
    /** Ready rows without their vectors, so a cache can tell which ones changed. */
    @Select("""
            SELECT problem_id, content_hash, embedding_model FROM problem_search_index
            WHERE status = 'READY' AND embedding IS NOT NULL
            """)
    List<ProblemSearchIndexEntity> readyHeads();

    /** Takes a due row for one embedding attempt; 0 when another instance took it first. */
    @Update("""
            UPDATE problem_search_index SET next_attempt_at = #{leaseUntil}
            WHERE problem_id = #{problemId} AND content_hash = #{contentHash}
              AND status IN ('PENDING', 'FAILED') AND next_attempt_at <= #{now}
            """)
    int claim(@Param("problemId") Long problemId, @Param("contentHash") String contentHash,
              @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    /** Drops the rows of problems that are no longer public and active. */
    @Delete("""
            DELETE i FROM problem_search_index i
            LEFT JOIN problems p ON p.id = i.problem_id AND p.deleted = 0 AND p.deleted_at IS NULL
                AND p.archived_at IS NULL AND p.visibility = 'PUBLIC'
            WHERE p.id IS NULL
            """)
    int deleteNotPublic();

    /** Once the configured embedding model changes, rows made by another model are embedded again. */
    @Update("""
            UPDATE problem_search_index
            SET status = 'PENDING', attempts = 0, next_attempt_at = #{now}, updated_at = #{now}
            WHERE status = 'READY' AND (embedding_model <> #{model} OR embedding_dimension <> #{dimension})
            """)
    int requeueOtherModels(@Param("model") String model, @Param("dimension") int dimension, @Param("now") Instant now);
}
