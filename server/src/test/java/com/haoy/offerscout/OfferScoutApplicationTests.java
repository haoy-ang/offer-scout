package com.haoy.offerscout;

import com.haoy.offerscout.observability.TokenUsageChatModel;
import com.haoy.offerscout.observability.TokenUsageRecorder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("requires-infra")
@SpringBootTest
class OfferScoutApplicationTests {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private TokenUsageRecorder tokenUsageRecorder;

    @Test
    void contextLoads() {
    }

    /**
     * 装配断言：业务代码（6 处）都是按<b>类型</b>注入 {@code ChatModel} 的，
     * 这里拿到的必须是记账装饰器。
     * <p>
     * <b>为什么这条断言值得单独写？</b>装饰器没生效时不会有任何报错、任何日志 ——
     * 表现只是"埋点数据永远是 0"。这种"改了但没生效"的静默失败，
     * 只能靠一条断言在 CI 里守住。
     */
    @Test
    void chatModelIsWrappedByTokenMeteredDecorator() {
        assertNotNull(chatModel);
        assertInstanceOf(TokenUsageChatModel.class, chatModel,
                "拿到的不是记账装饰器：@Primary 没生效，token 埋点等于没做");
        assertNotNull(tokenUsageRecorder);
    }
}
