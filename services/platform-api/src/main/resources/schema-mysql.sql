CREATE TABLE IF NOT EXISTS agent (
    agent_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    role_description TEXT NULL,
    capabilities JSON NULL,
    tools JSON NULL,
    PRIMARY KEY (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_account (
    user_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO user_account (user_id, created_at, updated_at)
VALUES ('anonymous', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

SET @ac_agent_has_role_description := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent'
      AND COLUMN_NAME = 'role_description'
);
SET @ac_agent_add_role_description_sql := IF(
    @ac_agent_has_role_description = 0,
    'ALTER TABLE agent ADD COLUMN role_description TEXT NULL AFTER display_name',
    'SELECT 1'
);
PREPARE ac_agent_add_role_description_stmt FROM @ac_agent_add_role_description_sql;
EXECUTE ac_agent_add_role_description_stmt;
DEALLOCATE PREPARE ac_agent_add_role_description_stmt;

SET @ac_agent_has_capabilities := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent'
      AND COLUMN_NAME = 'capabilities'
);
SET @ac_agent_add_capabilities_sql := IF(
    @ac_agent_has_capabilities = 0,
    'ALTER TABLE agent ADD COLUMN capabilities JSON NULL AFTER role_description',
    'SELECT 1'
);
PREPARE ac_agent_add_capabilities_stmt FROM @ac_agent_add_capabilities_sql;
EXECUTE ac_agent_add_capabilities_stmt;
DEALLOCATE PREPARE ac_agent_add_capabilities_stmt;

SET @ac_agent_has_tools := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent'
      AND COLUMN_NAME = 'tools'
);
SET @ac_agent_add_tools_sql := IF(
    @ac_agent_has_tools = 0,
    'ALTER TABLE agent ADD COLUMN tools JSON NULL AFTER capabilities',
    'SELECT 1'
);
PREPARE ac_agent_add_tools_stmt FROM @ac_agent_add_tools_sql;
EXECUTE ac_agent_add_tools_stmt;
DEALLOCATE PREPARE ac_agent_add_tools_stmt;

CREATE TABLE IF NOT EXISTS task (
    task_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL DEFAULT 'anonymous',
    trace_id VARCHAR(128) NOT NULL,
    created_by_task_id VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    depth INT NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    context_text TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (task_id),
    KEY idx_task_user_trace_created (user_id, trace_id, created_at, task_id),
    KEY idx_task_trace_created (trace_id, created_at, task_id),
    KEY idx_task_status_created (status, created_at, task_id),
    KEY idx_task_user_status_created (user_id, status, created_at, task_id),
    KEY idx_task_agent_status (agent_id, status),
    KEY idx_task_created_by (created_by_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ac_task_has_user_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'user_id'
);
SET @ac_task_add_user_id_sql := IF(
    @ac_task_has_user_id = 0,
    'ALTER TABLE task ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''anonymous'' AFTER task_id',
    'SELECT 1'
);
PREPARE ac_task_add_user_id_stmt FROM @ac_task_add_user_id_sql;
EXECUTE ac_task_add_user_id_stmt;
DEALLOCATE PREPARE ac_task_add_user_id_stmt;

CREATE TABLE IF NOT EXISTS task_dependency (
    parent_task_id VARCHAR(128) NOT NULL,
    child_task_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (parent_task_id, child_task_id),
    KEY idx_task_dependency_child (child_task_id, parent_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ac_task_has_parent_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'parent_id'
);
SET @ac_task_migrate_parent_id_sql := IF(
    @ac_task_has_parent_id > 0,
    'INSERT IGNORE INTO task_dependency (parent_task_id, child_task_id) SELECT parent_id, task_id FROM task WHERE parent_id IS NOT NULL',
    'SELECT 1'
);
PREPARE ac_task_migrate_parent_id_stmt FROM @ac_task_migrate_parent_id_sql;
EXECUTE ac_task_migrate_parent_id_stmt;
DEALLOCATE PREPARE ac_task_migrate_parent_id_stmt;
SET @ac_task_drop_parent_id_sql := IF(
    @ac_task_has_parent_id > 0,
    'ALTER TABLE task DROP COLUMN parent_id',
    'SELECT 1'
);
PREPARE ac_task_drop_parent_id_stmt FROM @ac_task_drop_parent_id_sql;
EXECUTE ac_task_drop_parent_id_stmt;
DEALLOCATE PREPARE ac_task_drop_parent_id_stmt;

SET @ac_task_has_child_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'child_id'
);
SET @ac_task_migrate_child_id_sql := IF(
    @ac_task_has_child_id > 0,
    'INSERT IGNORE INTO task_dependency (parent_task_id, child_task_id) SELECT task_id, child_id FROM task WHERE child_id IS NOT NULL',
    'SELECT 1'
);
PREPARE ac_task_migrate_child_id_stmt FROM @ac_task_migrate_child_id_sql;
EXECUTE ac_task_migrate_child_id_stmt;
DEALLOCATE PREPARE ac_task_migrate_child_id_stmt;
SET @ac_task_drop_child_id_sql := IF(
    @ac_task_has_child_id > 0,
    'ALTER TABLE task DROP COLUMN child_id',
    'SELECT 1'
);
PREPARE ac_task_drop_child_id_stmt FROM @ac_task_drop_child_id_sql;
EXECUTE ac_task_drop_child_id_stmt;
DEALLOCATE PREPARE ac_task_drop_child_id_stmt;

CREATE TABLE IF NOT EXISTS invocation (
    invocation_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL DEFAULT 'anonymous',
    task_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (invocation_id),
    KEY idx_invocation_user_task_created (user_id, task_id, created_at, invocation_id),
    KEY idx_invocation_task_created (task_id, created_at, invocation_id),
    KEY idx_invocation_agent_status_created (agent_id, status, created_at, invocation_id),
    KEY idx_invocation_user_agent_status_created (user_id, agent_id, status, created_at, invocation_id),
    KEY idx_invocation_trace_created (trace_id, created_at, invocation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ac_invocation_has_user_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'invocation' AND COLUMN_NAME = 'user_id'
);
SET @ac_invocation_add_user_id_sql := IF(
    @ac_invocation_has_user_id = 0,
    'ALTER TABLE invocation ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''anonymous'' AFTER invocation_id',
    'SELECT 1'
);
PREPARE ac_invocation_add_user_id_stmt FROM @ac_invocation_add_user_id_sql;
EXECUTE ac_invocation_add_user_id_stmt;
DEALLOCATE PREPARE ac_invocation_add_user_id_stmt;

CREATE TABLE IF NOT EXISTS chat_thread (
    thread_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL DEFAULT 'anonymous',
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    -- trace_id 是 thread 的唯一 trace（spec v1.1 §2.6）；同一个 thread 内的任务共享该 trace。
    trace_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (thread_id),
    KEY idx_chat_thread_user_updated (user_id, updated_at, thread_id),
    KEY idx_chat_thread_trace_id (trace_id),
    KEY idx_chat_thread_updated (updated_at, thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ac_chat_thread_has_user_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_thread' AND COLUMN_NAME = 'user_id'
);
SET @ac_chat_thread_add_user_id_sql := IF(
    @ac_chat_thread_has_user_id = 0,
    'ALTER TABLE chat_thread ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''anonymous'' AFTER thread_id',
    'SELECT 1'
);
PREPARE ac_chat_thread_add_user_id_stmt FROM @ac_chat_thread_add_user_id_sql;
EXECUTE ac_chat_thread_add_user_id_stmt;
DEALLOCATE PREPARE ac_chat_thread_add_user_id_stmt;

-- 兼容已有库：把老的 UNIQUE KEY uk_chat_thread_trace_id 删掉。
-- `ALTER TABLE ... DROP INDEX IF EXISTS` 只在 MySQL 8.0.29+ 支持，更早版本会报语法错；
-- 用 information_schema 查 + 动态 SQL 兼容 MySQL 5.7 / 8.0 全部版本。
SET @ac_has_uk_chat_thread_trace_id := (
    SELECT COUNT(1) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_thread'
      AND INDEX_NAME = 'uk_chat_thread_trace_id'
);
SET @ac_drop_uk_chat_thread_trace_id_sql := IF(
    @ac_has_uk_chat_thread_trace_id > 0,
    'ALTER TABLE chat_thread DROP INDEX uk_chat_thread_trace_id',
    'SELECT 1'
);
PREPARE ac_drop_uk_chat_thread_trace_id_stmt FROM @ac_drop_uk_chat_thread_trace_id_sql;
EXECUTE ac_drop_uk_chat_thread_trace_id_stmt;
DEALLOCATE PREPARE ac_drop_uk_chat_thread_trace_id_stmt;

-- MODEL_SYNC(ChatMessage) — 改字段时四处同步（无 codegen）：
--   · services/platform-api/src/main/java/com/agentcrossing/platform/domain/message/ChatMessage.java
--   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/ChatMessageResponse.java
--   · services/platform-web/lib/api.ts (export type ChatMessage)
--   · services/platform-api/src/main/resources/schema-mysql.sql (chat_message)  ← 本文件
--     + services/platform-api/src/main/resources/mapper/ChatMessageMapper.xml
CREATE TABLE IF NOT EXISTS chat_message (
    message_id VARCHAR(128) NOT NULL,
    thread_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    invocation_id VARCHAR(128) NULL,
    task_id VARCHAR(128) NULL,
    agent_id VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (message_id),
    KEY idx_chat_message_thread_created (thread_id, created_at, message_id),
    KEY idx_chat_message_invocation_created (invocation_id, created_at, message_id),
    KEY idx_chat_message_task_created (task_id, created_at, message_id),
    KEY idx_chat_message_agent_created (agent_id, created_at, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ac_chat_message_has_agent_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND COLUMN_NAME = 'agent_id'
);
SET @ac_chat_message_add_agent_id_sql := IF(
    @ac_chat_message_has_agent_id = 0,
    'ALTER TABLE chat_message ADD COLUMN agent_id VARCHAR(128) NULL AFTER task_id',
    'SELECT 1'
);
PREPARE ac_chat_message_add_agent_id_stmt FROM @ac_chat_message_add_agent_id_sql;
EXECUTE ac_chat_message_add_agent_id_stmt;
DEALLOCATE PREPARE ac_chat_message_add_agent_id_stmt;

SET @ac_chat_message_has_agent_created_idx := (
    SELECT COUNT(1) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND INDEX_NAME = 'idx_chat_message_agent_created'
);
SET @ac_chat_message_add_agent_created_idx_sql := IF(
    @ac_chat_message_has_agent_created_idx = 0,
    'ALTER TABLE chat_message ADD INDEX idx_chat_message_agent_created (agent_id, created_at, message_id)',
    'SELECT 1'
);
PREPARE ac_chat_message_add_agent_created_idx_stmt FROM @ac_chat_message_add_agent_created_idx_sql;
EXECUTE ac_chat_message_add_agent_created_idx_stmt;
DEALLOCATE PREPARE ac_chat_message_add_agent_created_idx_stmt;

UPDATE chat_message cm
INNER JOIN task t ON cm.task_id = t.task_id
SET cm.agent_id = t.agent_id
WHERE cm.agent_id IS NULL AND cm.task_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_context_cursor (
    user_id VARCHAR(128) NOT NULL,
    thread_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    last_injected_created_at TIMESTAMP(3) NULL,
    last_injected_message_id VARCHAR(128) NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id, thread_id, agent_id),
    KEY idx_agent_context_cursor_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_session (
    user_id VARCHAR(128) NOT NULL,
    thread_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_session_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id, thread_id, agent_id, provider),
    KEY idx_agent_session_trace (trace_id, agent_id, provider),
    KEY idx_agent_session_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ac_agent_session_has_thread_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_session'
      AND COLUMN_NAME = 'thread_id'
);
SET @ac_agent_session_add_thread_id_sql := IF(
    @ac_agent_session_has_thread_id = 0,
    'ALTER TABLE agent_session ADD COLUMN thread_id VARCHAR(128) NULL AFTER user_id',
    'SELECT 1'
);
PREPARE ac_agent_session_add_thread_id_stmt FROM @ac_agent_session_add_thread_id_sql;
EXECUTE ac_agent_session_add_thread_id_stmt;
DEALLOCATE PREPARE ac_agent_session_add_thread_id_stmt;

UPDATE agent_session s
INNER JOIN chat_thread t
   ON t.user_id = s.user_id
  AND t.trace_id = s.trace_id
SET s.thread_id = t.thread_id
WHERE s.thread_id IS NULL;

-- 旧版本 agent_session 以 trace_id 作为主键。当前产品语义是一 thread 一 provider session；
-- 无法回填 thread_id 的历史 session 已不可被查询复用，迁移时丢弃，后续执行会自动新建 provider session。
DELETE FROM agent_session
WHERE thread_id IS NULL OR thread_id = '';

-- 如果同一个 thread/agent/provider 下曾因旧 trace 主键写入多行，保留 updated_at 最新的一行。
DELETE s1 FROM agent_session s1
INNER JOIN agent_session s2
   ON s1.user_id = s2.user_id
  AND s1.thread_id = s2.thread_id
  AND s1.agent_id = s2.agent_id
  AND s1.provider = s2.provider
  AND (
      s1.updated_at < s2.updated_at
      OR (s1.updated_at = s2.updated_at AND s1.trace_id < s2.trace_id)
  );

SET @ac_agent_session_thread_nullable := (
    SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_session'
      AND COLUMN_NAME = 'thread_id'
);
SET @ac_agent_session_thread_not_null_sql := IF(
    @ac_agent_session_thread_nullable = 'YES',
    'ALTER TABLE agent_session MODIFY COLUMN thread_id VARCHAR(128) NOT NULL',
    'SELECT 1'
);
PREPARE ac_agent_session_thread_not_null_stmt FROM @ac_agent_session_thread_not_null_sql;
EXECUTE ac_agent_session_thread_not_null_stmt;
DEALLOCATE PREPARE ac_agent_session_thread_not_null_stmt;

SET @ac_agent_session_primary_columns := (
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_session'
      AND INDEX_NAME = 'PRIMARY'
);
SET @ac_agent_session_thread_pk_sql := IF(
    @ac_agent_session_primary_columns <> 'user_id,thread_id,agent_id,provider',
    'ALTER TABLE agent_session DROP PRIMARY KEY, ADD PRIMARY KEY (user_id, thread_id, agent_id, provider)',
    'SELECT 1'
);
PREPARE ac_agent_session_thread_pk_stmt FROM @ac_agent_session_thread_pk_sql;
EXECUTE ac_agent_session_thread_pk_stmt;
DEALLOCATE PREPARE ac_agent_session_thread_pk_stmt;

SET @ac_agent_session_has_thread_idx := (
    SELECT COUNT(1) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_session'
      AND INDEX_NAME = 'idx_agent_session_thread'
);
SET @ac_agent_session_drop_thread_idx_sql := IF(
    @ac_agent_session_has_thread_idx > 0,
    'ALTER TABLE agent_session DROP INDEX idx_agent_session_thread',
    'SELECT 1'
);
PREPARE ac_agent_session_drop_thread_idx_stmt FROM @ac_agent_session_drop_thread_idx_sql;
EXECUTE ac_agent_session_drop_thread_idx_stmt;
DEALLOCATE PREPARE ac_agent_session_drop_thread_idx_stmt;

CREATE TABLE IF NOT EXISTS invocation_message (
    message_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL DEFAULT 'anonymous',
    invocation_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NULL,
    raw_payload JSON NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (message_id),
    KEY idx_invocation_message_user_trace_created (user_id, trace_id, created_at, message_id),
    KEY idx_invocation_message_invocation_created (invocation_id, created_at, message_id),
    KEY idx_invocation_message_trace_created (trace_id, created_at, message_id),
    KEY idx_invocation_message_task_created (task_id, created_at, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ac_invocation_message_has_user_id := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'invocation_message' AND COLUMN_NAME = 'user_id'
);
SET @ac_invocation_message_add_user_id_sql := IF(
    @ac_invocation_message_has_user_id = 0,
    'ALTER TABLE invocation_message ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''anonymous'' AFTER message_id',
    'SELECT 1'
);
PREPARE ac_invocation_message_add_user_id_stmt FROM @ac_invocation_message_add_user_id_sql;
EXECUTE ac_invocation_message_add_user_id_stmt;
DEALLOCATE PREPARE ac_invocation_message_add_user_id_stmt;

CREATE TABLE IF NOT EXISTS realtime_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    thread_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_realtime_event_thread_event (thread_id, event_id),
    KEY idx_realtime_event_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
