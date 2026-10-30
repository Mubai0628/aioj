package com.aioj.next.ai.domain.retention;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SQL of the retention job (plan S6). Each delete is bounded: one conversation, or one batch
 * of runs, per transaction. Snowflake ids grow with time, so an id bound keeps the scans of the
 * run table to its old rows; the time condition next to it is what decides.
 */
@Component
public class RetentionStore {
    /** MyBatis-Plus snowflakes: milliseconds since this epoch, above 22 bits of worker and sequence. */
    static final long SNOWFLAKE_EPOCH_MILLIS = 1_288_834_974_657L;
    private static final String LOCK_NAME = "aioj.ai.retention";

    /** Deleted long enough ago; a draft workspace's conversation is the workspace's; contest records wait longer. */
    private static final String DELETED_CONVERSATIONS = """
             FROM ai_conversations c
            WHERE c.deleted_at < :cutoff
              AND NOT EXISTS (SELECT 1 FROM ai_problem_draft_workspaces w WHERE w.conversation_id = c.id)
              AND (c.deleted_at < :contestCutoff
                   OR (c.contest_run_id IS NULL
                       AND NOT EXISTS (SELECT 1 FROM ai_turns t JOIN ai_guard_decisions g ON g.turn_id = t.id
                                        WHERE t.conversation_id = c.id AND g.contest_run_id IS NOT NULL)
                       AND NOT EXISTS (SELECT 1 FROM ai_contest_assistance_turns a WHERE a.conversation_id = c.id)))
            """;
    /** Student turns only: draft workspaces and generation jobs keep their runs while the draft lives. */
    private static final String EXPIRED_STUDENT_RUNS = """
             FROM ai_agent_runs r
            WHERE r.workspace_id IS NULL AND r.id < :idBound AND r.started_at < :cutoff
              AND NOT EXISTS (SELECT 1 FROM ai_problem_draft_workspaces w WHERE w.conversation_id = r.conversation_id)
              AND NOT EXISTS (SELECT 1 FROM ai_problem_draft_generation_jobs j WHERE j.agent_run_id = r.id)
            """;
    private static final String DELETED_DRAFT_RUNS = """
             FROM ai_agent_runs r
             JOIN ai_problem_draft_workspaces w ON w.id = r.workspace_id
             JOIN problem_drafts d ON d.id = COALESCE(w.current_draft_id, w.candidate_draft_id)
            WHERE d.deleted_at < :cutoff
            """;
    /** The current package of a live draft stays; replaced, failed and unfinished ones, and a deleted draft's, go. */
    private static final String RETIRED_ARTIFACTS = """
             FROM ai_problem_draft_testcase_artifacts a
            WHERE a.status <> 'PURGED'
              AND ((a.status IN ('SUPERSEDED', 'FAILED', 'WRITING') AND a.updated_at < :retiredCutoff)
                   OR EXISTS (SELECT 1 FROM problem_drafts d WHERE d.id = a.draft_id AND d.deleted_at < :draftCutoff))
            """;
    /** Rows of a conversation besides its turns and messages; the user id leads most of these indexes. */
    private static final List<String> CONVERSATION_ROWS = List.of(
            "DELETE FROM ai_turn_digests WHERE user_id = :user AND conversation_id = :conversation",
            "DELETE FROM ai_conversation_summaries WHERE user_id = :user AND conversation_id = :conversation",
            "DELETE FROM ai_conversation_task_states WHERE user_id = :user AND conversation_id = :conversation",
            "DELETE FROM ai_conversation_problems WHERE conversation_id = :conversation",
            "DELETE FROM ai_clarification_answers WHERE user_id = :user AND conversation_id = :conversation",
            "DELETE FROM ai_clarification_requests WHERE user_id = :user AND conversation_id = :conversation",
            "DELETE FROM ai_code_snapshots WHERE user_id = :user AND conversation_id = :conversation");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transactions;

    public RetentionStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    record Conversation(String id, long userId) {
    }

    record Artifact(long id, String storagePath) {
    }

    record PurgedRows(long conversations, long messages, long otherRows, long runTraces) {
        static final PurgedRows NONE = new PurgedRows(0, 0, 0, 0);
    }

    /** Runs the body while holding a MySQL named lock, so one instance purges at a time; false when another holds it. */
    boolean withLock(Runnable body) {
        Boolean ran = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            if (!namedLock(connection, "SELECT GET_LOCK(?, 0)")) {
                return false;
            }
            try {
                body.run();
                return true;
            } finally {
                namedLock(connection, "SELECT RELEASE_LOCK(?)");
            }
        });
        return Boolean.TRUE.equals(ran);
    }

    /** After the given id, so one that cannot be purged does not hold up the ones behind it. */
    List<Conversation> deletedConversations(LocalDateTime cutoff, LocalDateTime contestCutoff, String afterId, int limit) {
        return named.query("SELECT c.id, c.user_id" + DELETED_CONVERSATIONS + " AND c.id > :after ORDER BY c.id LIMIT :limit",
                conversationCutoffs(cutoff, contestCutoff).addValue("after", afterId).addValue("limit", limit),
                (row, index) -> new Conversation(row.getString(1), row.getLong(2)));
    }

    /** Everything of one conversation, in one transaction; nothing when it is no longer deleted that long ago. */
    PurgedRows purgeConversation(Conversation conversation, LocalDateTime cutoff) {
        return transactions.execute(status -> {
            MapSqlParameterSource params = new MapSqlParameterSource("conversation", conversation.id())
                    .addValue("user", conversation.userId());
            List<LocalDateTime> deletedAt = named.queryForList(
                    "SELECT deleted_at FROM ai_conversations WHERE id = :conversation FOR UPDATE", params, LocalDateTime.class);
            if (deletedAt.isEmpty() || deletedAt.get(0) == null || !deletedAt.get(0).isBefore(cutoff)) {
                return PurgedRows.NONE;
            }
            List<String> turns = named.queryForList("SELECT id FROM ai_turns WHERE conversation_id = :conversation",
                    params, String.class);
            long runTraces = 0;
            long otherRows = 0;
            if (!turns.isEmpty()) {
                MapSqlParameterSource turnParams = new MapSqlParameterSource("turns", turns);
                runTraces = deleteRuns(named.queryForList("SELECT id FROM ai_agent_runs WHERE turn_id IN (:turns)",
                        turnParams, Long.class));
                otherRows += named.update("DELETE FROM ai_policy_snapshots WHERE turn_id IN (:turns)", turnParams);
            }
            for (String sql : CONVERSATION_ROWS) {
                otherRows += named.update(sql, params);
            }
            otherRows += named.update("DELETE FROM ai_turns WHERE conversation_id = :conversation", params);
            long messages = named.update("DELETE FROM ai_messages WHERE conversation_id = :conversation", params);
            long conversations = named.update("DELETE FROM ai_conversations WHERE id = :conversation", params);
            return new PurgedRows(conversations, messages, otherRows, runTraces);
        });
    }

    List<Long> expiredStudentRuns(LocalDateTime cutoff, int limit) {
        return named.queryForList("SELECT r.id" + EXPIRED_STUDENT_RUNS + " ORDER BY r.id LIMIT :limit",
                studentRunCutoff(cutoff).addValue("limit", limit), Long.class);
    }

    List<Long> deletedDraftRuns(LocalDateTime cutoff, int limit) {
        return named.queryForList("SELECT r.id" + DELETED_DRAFT_RUNS + " ORDER BY r.id LIMIT :limit",
                new MapSqlParameterSource("cutoff", cutoff).addValue("limit", limit), Long.class);
    }

    /** The runs with their tool calls and context manifests, in one transaction; the number of rows deleted. */
    long purgeRuns(List<Long> runIds) {
        Long deleted = transactions.execute(status -> deleteRuns(runIds));
        return deleted == null ? 0 : deleted;
    }

    /** After the given id, so a file that cannot be deleted does not hold up the ones behind it. */
    List<Artifact> retiredArtifacts(LocalDateTime retiredCutoff, LocalDateTime draftCutoff, long afterId, int limit) {
        return named.query("SELECT a.id, a.storage_path" + RETIRED_ARTIFACTS + " AND a.id > :after ORDER BY a.id LIMIT :limit",
                artifactCutoffs(retiredCutoff, draftCutoff).addValue("after", afterId).addValue("limit", limit),
                (row, index) -> new Artifact(row.getLong(1), row.getString(2)));
    }

    /** What a pass would delete now, for the dry run; turn-level rows other than messages are not counted. */
    Map<String, Long> count(LocalDateTime conversationCutoff, LocalDateTime contestCutoff, LocalDateTime runCutoff,
                            LocalDateTime draftCutoff, LocalDateTime retiredCutoff) {
        MapSqlParameterSource conversations = conversationCutoffs(conversationCutoff, contestCutoff);
        MapSqlParameterSource studentRuns = studentRunCutoff(runCutoff);
        MapSqlParameterSource draftRuns = new MapSqlParameterSource("cutoff", draftCutoff);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("conversations", one("SELECT COUNT(*)" + DELETED_CONVERSATIONS, conversations));
        counts.put("messages", one("SELECT COUNT(*) FROM ai_messages m WHERE m.conversation_id IN (SELECT c.id"
                + DELETED_CONVERSATIONS + ")", conversations));
        counts.put("run_traces", traces(EXPIRED_STUDENT_RUNS, studentRuns) + traces(DELETED_DRAFT_RUNS, draftRuns));
        counts.put("artifact_files", one("SELECT COUNT(*)" + RETIRED_ARTIFACTS,
                artifactCutoffs(retiredCutoff, draftCutoff)));
        return counts;
    }

    /** The highest id a row created before the cutoff can have, plus one. */
    static long idBound(LocalDateTime cutoff, ZoneId zone) {
        return (cutoff.atZone(zone).toInstant().toEpochMilli() - SNOWFLAKE_EPOCH_MILLIS) << 22;
    }

    private long deleteRuns(List<Long> runIds) {
        if (runIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("runs", runIds);
        return named.update("DELETE FROM ai_tool_calls WHERE agent_run_id IN (:runs)", params)
                + named.update("DELETE FROM ai_context_manifests WHERE agent_run_id IN (:runs)", params)
                + named.update("DELETE FROM ai_agent_runs WHERE id IN (:runs)", params);
    }

    private long traces(String runs, MapSqlParameterSource params) {
        return one("SELECT COUNT(*) FROM ai_tool_calls t WHERE t.agent_run_id IN (SELECT r.id" + runs + ")", params)
                + one("SELECT COUNT(*) FROM ai_context_manifests m WHERE m.agent_run_id IN (SELECT r.id" + runs + ")", params)
                + one("SELECT COUNT(*)" + runs, params);
    }

    private long one(String sql, MapSqlParameterSource params) {
        Long value = named.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static MapSqlParameterSource conversationCutoffs(LocalDateTime cutoff, LocalDateTime contestCutoff) {
        return new MapSqlParameterSource("cutoff", cutoff).addValue("contestCutoff", contestCutoff);
    }

    private static MapSqlParameterSource studentRunCutoff(LocalDateTime cutoff) {
        return new MapSqlParameterSource("cutoff", cutoff).addValue("idBound", idBound(cutoff, ZoneId.systemDefault()));
    }

    private static MapSqlParameterSource artifactCutoffs(LocalDateTime retiredCutoff, LocalDateTime draftCutoff) {
        return new MapSqlParameterSource("retiredCutoff", retiredCutoff).addValue("draftCutoff", draftCutoff);
    }

    private static boolean namedLock(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }
}
