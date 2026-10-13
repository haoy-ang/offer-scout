package com.haoy.offerscout.user;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 用户仓储（JDBC 实现）。
 * <p>
 * 「注册时用户名唯一」这件事，靠的是数据库上的 {@code UNIQUE KEY uk_username}，
 * 而不是"先 SELECT 查有没有，没有就 INSERT" —— 后者存在并发窗口：
 * 两个请求同时查到"不存在"，然后都去 INSERT，就重复了。
 * 正确做法是让唯一索引兜底，捕获 {@link DuplicateKeyException} 再转成业务错误。
 */
@Repository
public class UserRepository {

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password"),
            rs.getString("nickname"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private static final String COLUMNS = "id, username, password, nickname, created_at";

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findByUsername(String username) {
        List<User> result = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM app_user WHERE username = ?", ROW_MAPPER, username);
        return result.stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        List<User> result = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM app_user WHERE id = ?", ROW_MAPPER, id);
        return result.stream().findFirst();
    }

    /**
     * 插入用户并回填自增主键。
     *
     * @return 新用户的 ID
     * @throws DuplicateKeyException 用户名已存在（由唯一索引抛出）
     */
    public long insert(String username, String passwordHash, String nickname) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO app_user (username, password, nickname) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, nickname);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入用户后没有拿到自增主键");
        }
        return key.longValue();
    }
}
