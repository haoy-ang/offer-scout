package com.haoy.offerscout.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 极简「给已有表补列」的迁移器。
 * <p>
 * <b>为什么需要它？</b>{@code db/schema.sql} 里的建表语句都带 {@code IF NOT EXISTS}，
 * 所以对**全新的库**没问题；但对**已经存在的库**，{@code IF NOT EXISTS} 不会把后来新增的列补上。
 * 而 MySQL 8 没有 {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}（那是 MariaDB 的扩展），
 * 纯 SQL 脚本里做不到幂等。
 * <p>
 * 正式项目的正解是引入 Flyway / Liquibase，让每次变更成为一条有版本号的迁移记录。
 * 这个项目暂时不引第三方迁移框架，就用这段 30 行的代码顶上 —— 逻辑很简单：
 * <b>先查 information_schema，缺什么补什么</b>，查不到才执行 DDL，所以反复启动也只有第一次会真的改表。
 * <p>
 * <b>为什么用 {@link ApplicationRunner} 而不是 {@code @PostConstruct}？</b>
 * {@code spring.sql.init} 的建表脚本是在 Bean 初始化阶段跑的，
 * 普通 Bean 的 {@code @PostConstruct} 可能排在它前面 —— 那样迁移器会去查一个还不存在的表。
 * {@code ApplicationRunner} 在整个上下文刷新完成之后才执行，顺序上是安全的。
 * <p>
 * <b>⚠️ 生产环境的警告</b>：真上线时不要用应用启动来自动改表结构。
 * 大表 {@code ALTER} 可能锁表几十分钟，把应用启动和 DDL 绑在一起会导致发布失败、甚至雪崩。
 * 生产做法是「先单独执行 DDL（必要时用 gh-ost / pt-online-schema-change），确认无风险，再发代码」。
 * 这里这么做，是因为本地开发库表小、无流量，方便。
 */
@Component
@Slf4j
public class SchemaMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 鉴权上线后，会话表需要记录归属用户
        addColumnIfMissing("conversation", "user_id",
                "ALTER TABLE conversation ADD COLUMN user_id BIGINT NULL COMMENT '归属用户，NULL 表示鉴权上线前的无主会话'");

        // 会话列表改成「按用户查 + 按活跃时间排序」，配套的索引
        addIndexIfMissing("conversation", "idx_user_updated",
                "CREATE INDEX idx_user_updated ON conversation (user_id, updated_at)");
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        if (columnExists(table, column)) {
            return;
        }
        log.info("迁移：{}.{} 不存在，执行 DDL", table, column);
        jdbcTemplate.execute(ddl);
        log.info("迁移完成：{}.{} 已新增", table, column);
    }

    private void addIndexIfMissing(String table, String indexName, String ddl) {
        if (indexExists(table, indexName)) {
            return;
        }
        log.info("迁移：{} 表上索引 {} 不存在，执行 DDL", table, indexName);
        jdbcTemplate.execute(ddl);
        log.info("迁移完成：索引 {} 已新增", indexName);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private boolean indexExists(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                """, Integer.class, table, indexName);
        return count != null && count > 0;
    }
}
