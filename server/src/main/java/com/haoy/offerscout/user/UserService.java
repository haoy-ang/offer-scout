package com.haoy.offerscout.user;

import com.haoy.offerscout.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户业务逻辑：注册与登录校验。
 * <p>
 * <b>密码为什么要用 BCrypt 而不是 MD5/SHA？</b>因为 MD5/SHA 是"快"哈希 —— 设计目标就是算得快，
 * 显卡一秒钟能撞几十亿次，配合彩虹表基本等于明文。BCrypt 有两个关键设计：
 * <ul>
 *   <li><b>慢</b>：内置可调的工作因子（cost），每次校验要几十毫秒，暴力破解的代价指数级上升</li>
 *   <li><b>自带盐</b>：盐值直接编进密文里（所以同一密码两次加密结果不同），不用另建盐字段，
 *       也天然免疫彩虹表</li>
 * </ul>
 * 校验时不需要"解密"，用 {@code matches(明文, 密文)} 让 BCrypt 用密文里记录的盐重算一遍再比对。
 */
@Service
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册。
     * <p>
     * 唯一性交给数据库的唯一索引保证，这里只负责把「索引冲突」翻译成人话。
     * 不用"先查再插"：那有并发窗口，两个请求同时通过检查就会插进两条同名记录。
     */
    public User register(String username, String rawPassword, String nickname) {
        String passwordHash = passwordEncoder.encode(rawPassword);
        try {
            long id = userRepository.insert(username, passwordHash, nickname);
            log.info("新用户注册成功：id={}, username={}", id, username);
            return new User(id, username, passwordHash, nickname, null);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "用户名「" + username + "」已被注册");
        }
    }

    /**
     * 登录校验。
     * <p>
     * <b>「用户不存在」和「密码错误」返回同一句提示</b>是刻意的：
     * 如果两者提示不同，攻击者就能靠报错差异批量枚举出哪些用户名真实存在
     * （这叫用户名枚举）。安全上宁可让用户多试一次，也不泄露账号是否存在。
     */
    public User authenticate(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));

        if (!passwordEncoder.matches(rawPassword, user.password())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }

    public UserVO findById(long id) {
        return userRepository.findById(id)
                .map(UserVO::of)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录"));
    }
}
