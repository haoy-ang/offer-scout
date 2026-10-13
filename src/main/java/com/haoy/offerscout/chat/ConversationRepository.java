package com.haoy.offerscout.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话元数据仓储（JDBC 实现）。
 * <p>
 * 这里刻意用 {@link JdbcTemplate} 而不是 ORM：表结构简单、SQL 就几条，
 * 手写 SQL 反而更透明 —— 面试时被问「这条 SQL 走了什么索引」你能直接答上来。
 * 等实体和关联关系复杂起来，再上 MyBatis / JPA 也不迟。
 * <p>
 * <b>鉴权上线后，这里的查询几乎全部带上 {@code WHERE user_id = ?}。</b>
 * 这不是"顺手加个过滤条件"，而是整个越权防护的落点：
 * 会话 ID 是前端传上来的，只按 ID 查就等于"知道 ID 就能读"。
 * 把归属条件下推到 SQL，越权在数据层就被挡掉，不依赖上层记得每次都判一遍。
 */
@Repository
public class ConversationRepository {

    private static final String COLUMNS = "id, user_id, title, message_count, created_at, updated_at";

    /**
     * 显式写 RowMapper 而不是依赖自动映射：
     * 列名到字段的对应关系一眼可见，也不受「驼峰转换开关」这类全局配置影响。
     */
    private static final RowMapper<Conversation> ROW_MAPPER = (rs, rowNum) -> new Conversation(
            rs.getString("id"),
            // 用 getObject 而不是 getLong：user_id 可为 NULL，而 getLong 遇到 NULL 会返回 0，
            // 会把"无主会话"错认成"0 号用户的会话"—— 这就是一个凭空造出来的越权。
            rs.getObject("user_id", Long.class),
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
     * 不存在才插入，返回实际插入的行数（1 = 新建，0 = 已存在）。
     * <p>
     * INSERT IGNORE 是 MySQL 方言，作用等于「冲突就跳过」：
     * 放在这里是为了让「每轮对话都 ensure 一次」成为幂等操作，
     * 不用先 SELECT 再 INSERT（那会有并发窗口，两个请求同时新建会话就撞主键了）。
     * <p>
     * <b>返回行数很关键：</b>调用方要靠它区分"我新建的会话"和"这个 ID 早就存在"。
     * 后者必须再查一次归属 —— 否则用户随手传一个别人的会话 ID，
     * 我们就会把新消息追加进别人的会话里。
     *
     * @return 1 表示新建成功；0 表示该 ID 已存在（归属未知，需另行校验）
     */
    public int insertIfAbsent(String id, String title, Long userId) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO conversation (id, title, message_count, user_id) VALUES (?, ?, 0, ?)",
                id, title, userId);
    }

    /** 刷新消息条数与活跃时间（updated_at 由 ON UPDATE CURRENT_TIMESTAMP 自动维护） */
    public void updateMessageCount(String id, int messageCount) {
        jdbcTemplate.update("UPDATE conversation SET message_count = ? WHERE id = ?", messageCount, id);
    }

    /**
     * 某个用户的会话列表：按最后活跃时间倒序，最近的排前面。
     * <p>
     * 走 {@code idx_user_updated (user_id, updated_at)} 这个联合索引：
     * user_id 等值匹配定位到范围，updated_at 在索引内已有序，ORDER BY 不需要额外排序。
     */
    public List<Conversation> findByUserOrderByUpdatedAtDesc(Long userId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM conversation WHERE user_id = ? ORDER BY updated_at DESC",
                ROW_MAPPER, userId);
    }

    public Optional<Conversation> findById(String id) {
        List<Conversation> result = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM conversation WHERE id = ?", ROW_MAPPER, id);
        return result.stream().findFirst();
    }

    /**
     * 按「会话 ID + 归属用户」删除。
     * <p>
     * {@code AND user_id = ?} 不能省：它会顺带让"删别人的会话"返回 0 行，
     * 变成一次无效操作，而不是一次成功的越权删除。
     *
     * @return 实际删除的行数（0 表示会话不存在，或不属于该用户）
     */
    public int deleteByIdAndUserId(String id, Long userId) {
        return jdbcTemplate.update(
                "DELETE FROM conversation WHERE id = ? AND user_id = ?", id, userId);
    }
}
