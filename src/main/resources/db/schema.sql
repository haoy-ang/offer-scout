-- =============================================================
-- OfferScout 表结构
--
-- 为什么连框架的表也自己建？
--   Spring AI 1.0.0 的 spring-ai-model-chat-memory-repository-jdbc 只带了
--   hsqldb / mariadb / postgresql / sqlserver 四份建表脚本，**没有 mysql**。
--   平台自动探测出 mysql 后会去找 schema-mysql.sql，找不到直接启动失败。
--   所以这里把配置改成 initialize-schema=never（不让它自己初始化），
--   由本文件负责建表。顺带两个 MySQL 适配：
--     1. MySQL 8 不支持 CREATE INDEX IF NOT EXISTS（那是 MariaDB 语法）
--        → 索引写进 CREATE TABLE 里的 KEY，配合 IF NOT EXISTS 天然幂等
--     2. conversation_id 由 VARCHAR(36) 放宽到 VARCHAR(64)，与 conversation 表一致
--
-- 全部语句都是 IF NOT EXISTS，每次启动执行一遍是幂等的
-- =============================================================

-- Spring AI 对话记忆表：消息正文存这里
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY
(
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话 ID',
    content         TEXT         NOT NULL COMMENT '消息正文（JSON）',
    type            VARCHAR(10)  NOT NULL COMMENT '消息类型：USER / ASSISTANT / SYSTEM / TOOL',
    `timestamp`     TIMESTAMP    NOT NULL COMMENT '写入时间',
    KEY idx_conversation_id_timestamp (conversation_id, `timestamp`),
    CONSTRAINT chk_memory_type CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Spring AI 对话记忆（由框架读写）';

-- 会话元数据表
-- 为什么消息正文不存这里？因为消息是框架（Spring AI）的领域模型，
-- 交给框架自己的仓库管理更稳；而「会话列表」是产品需求，框架不管，得自己建。
CREATE TABLE IF NOT EXISTS conversation
(
    id            VARCHAR(64)  NOT NULL COMMENT '会话 ID，与 SPRING_AI_CHAT_MEMORY.conversation_id 对应',
    title         VARCHAR(128) NOT NULL DEFAULT '新会话' COMMENT '会话标题，取首条用户消息前 30 字',
    message_count INT          NOT NULL DEFAULT 0 COMMENT '当前记忆窗口内的消息条数',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    PRIMARY KEY (id),
    KEY idx_updated_at (updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话元数据';
