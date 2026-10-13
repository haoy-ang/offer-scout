package com.haoy.offerscout.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话元数据仓储（JDBC 实现）。
 * <p>
 * 这里刻意用 {@link JdbcTemplate} 而不是 ORM：表结构简单、SQL 就三条，
 * 手写 SQL 反而更透明 —— 面试时被问「这条 SQL 走了什么索引」你能直接答上来。
 * 等实体和关联关系复杂起来，再上 MyBatis / JPA 也不迟。
 */
@Repository
public class ConversationRepository {

    /**
     * 显式写 RowMapper 而不是依赖自动映射：
     * 列名到字段的对应关系一眼可见，也不受「驼峰转换开关」这类全局配置影响。
     */
    private static final RowMapper<Conversation> ROW_MAPPER = (rs, rowNum) -> new Conversation(
            rs.getString("id"),
            rs.getString("title"),
            rs.getInt("message_count"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 不存在才插入。
     * <p>
     * INSERT IGNORE 是 MySQL 方言，作用等于「冲突就跳过」：
     * 放在这里是为了让「每轮对话都 ensure 一次」成为幂等操作，
     * 不用先 SELECT 再 INSERT（那会有并发窗口，两个请求同时新建会话就撞主键了）。
     */
    public void insertIfAbsent(String id, String title) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO conversation (id, title, message_count) VALUES (?, ?, 0)",
                id, title);
    }

    /** 刷新消息条数与活跃时间（updated_at 由 ON UPDATE CURRENT_TIMESTAMP 自动维护） */
    public void updateMessageCount(String id, int messageCount) {
        jdbcTemplate.update("UPDATE conversation SET message_count = ? WHERE id = ?", messageCount, id);
    }

    /** 会话列表：按最后活跃时间倒序，最近的排前面 */
    public List<Conversation> findAllOrderByUpdatedAtDesc() {
        return jdbcTemplate.query(
                "SELECT id, title, message_count, created_at, updated_at "
                        + "FROM conversation ORDER BY updated_at DESC",
                ROW_MAPPER);
    }

    public Optional<Conversation> findById(String id) {
        List<Conversation> result = jdbcTemplate.query(
                "SELECT id, title, message_count, created_at, updated_at FROM conversation WHERE id = ?",
                ROW_MAPPER, id);
        return result.stream().findFirst();
    }

    public int deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM conversation WHERE id = ?", id);
    }
}
