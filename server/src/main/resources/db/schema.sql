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

-- 用户表
-- 为什么叫 app_user 而不是 user？
--   MySQL 里 user 虽然不算保留字，但 mysql.user 是系统表，写 SQL 和排查时容易混；
--   换个名字能省掉一堆反引号，也更明确"这是本应用的业务用户"。
-- 为什么密码列留 100 字？
--   BCrypt 密文固定 60 字符，但预留一点空间，将来换 Argon2（密文更长）不用改表结构。
CREATE TABLE IF NOT EXISTS app_user
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户 ID，JWT 的 subject',
    username   VARCHAR(64)  NOT NULL COMMENT '登录名',
    password   VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，绝不存明文',
    nickname   VARCHAR(64)           DEFAULT NULL COMMENT '显示名',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='业务用户';

-- 会话元数据表
-- 为什么消息正文不存这里？因为消息是框架（Spring AI）的领域模型，
-- 交给框架自己的仓库管理更稳；而「会话列表」是产品需求，框架不管，得自己建。
CREATE TABLE IF NOT EXISTS conversation
(
    id            VARCHAR(64)  NOT NULL COMMENT '会话 ID，与 SPRING_AI_CHAT_MEMORY.conversation_id 对应',
    user_id       BIGINT                DEFAULT NULL COMMENT '归属用户；NULL 表示鉴权上线前遗留的无主会话',
    title         VARCHAR(128) NOT NULL DEFAULT '新会话' COMMENT '会话标题，取首条用户消息前 30 字',
    message_count INT          NOT NULL DEFAULT 0 COMMENT '当前记忆窗口内的消息条数',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    PRIMARY KEY (id),
    -- 会话列表是按用户查的，所以索引带上 user_id；updated_at 放后面用于排序
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话元数据';

-- 对话全量归档表（产品侧「历史记录」的唯一数据源）
-- 为什么要这张表？因为 SPRING_AI_CHAT_MEMORY 存的是**上下文窗口**，不是历史：
--   MessageWindowChatMemory 保存时会用「裁剪后的最近 N 条」覆盖原内容，
--   所以那张表里最多只有 maxMessages（本项目 50）条 —— 归档页滚到第 50 条就断了。
-- 正确做法是**把「上下文」和「归档」拆成两件事**：
--   · 上下文：继续用 SPRING_AI_CHAT_MEMORY + 滑动窗口，省 token，够模型用就行；
--   · 归档  ：本表 append-only，永不裁剪，归档页只读它。
-- 为什么不建外键指向 conversation？
--   删除会话时由业务代码按 conversation_id 显式清理（见 ConversationService#delete）。
--   加外键会让「先删元数据还是先删消息」的顺序变成硬约束，且并发删除时容易撞约束报错；
--   会话 ID 是 UUID、生命周期完全由本应用控制，外键在这里带来的是耦合而不是保护。
CREATE TABLE IF NOT EXISTS chat_message
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键，同时充当同会话内的顺序号',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话 ID，对应 conversation.id',
    role            VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT / SYSTEM',
    content         MEDIUMTEXT  NOT NULL COMMENT '消息正文（纯文本；框架那张表存的是 JSON，这张表只存给人看的文本）',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '写入时间，毫秒精度保证同秒内也有序',
    PRIMARY KEY (id),
    -- 归档页的查询形态就是「按会话取全部，按写入顺序排」→ (conversation_id, id) 一条索引全覆盖，
    -- 且 InnoDB 二级索引自带主键，回表都不需要（覆盖索引）
    KEY idx_conv_id (conversation_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对话全量归档（append-only，不受上下文窗口裁剪影响）';

-- -------------------------------------------------------------
-- 关于「给已有表加列」这件事
--
-- 上面两个 CREATE TABLE 都带 IF NOT EXISTS，所以**只对全新的库有效**：
-- 老库上 conversation 表已经存在，IF NOT EXISTS 不会补上后来新增的 user_id 列。
--
-- 而 MySQL 8 **没有** `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的扩展），
-- 所以这件事没法在纯 SQL 脚本里优雅地做成幂等。两条常见出路：
--   ① 引入专门的迁移工具（Flyway / Liquibase）—— 正式项目的正解，本项目暂不引入；
--   ② 在应用启动时用一段 Java 代码查 information_schema 再决定要不要 ALTER。
-- 本项目用 ②，见 config/SchemaMigrator。
--
-- 顺带一条经验：真上生产时，DDL 和发版要解耦 ——
-- 先单独执行 DDL，确认无锁表风险，再发应用代码。让应用启动时自动改表结构是很危险的。
-- -------------------------------------------------------------
