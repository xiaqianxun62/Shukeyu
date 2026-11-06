package com.yuyue.ai.tool;

import com.yuyue.entity.User;
import com.yuyue.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工具路由：命中就查、最多两个、失败不影响整轮问答。
 */
@ExtendWith(MockitoExtension.class)
class AiToolRouterTest {

    @Mock private UserMapper userMapper;

    /** 可编程的假工具 */
    private static final class FakeTool implements AiDataTool {
        private final String name;
        private final boolean supports;
        private final String result;
        private final boolean boom;

        FakeTool(String name, boolean supports, String result, boolean boom) {
            this.name = name;
            this.supports = supports;
            this.result = result;
            this.boom = boom;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public boolean supports(String question) {
            return supports;
        }

        @Override
        public String fetch(Long userId) {
            if (boom) {
                throw new IllegalStateException("db down");
            }
            return result;
        }
    }

    @Test
    @DisplayName("命中两个工具：事实按顺序拼接，名字全部返回")
    void runsSupportingTools() {
        AiToolRouter router = new AiToolRouter(List.of(
                new FakeTool("A", true, "事实A", false),
                new FakeTool("B", true, "事实B", false)));
        AiToolOutcome outcome = router.route(1L, "我的积分和球局");
        assertEquals(List.of("A", "B"), outcome.names());
        assertTrue(outcome.facts().contains("事实A"));
        assertTrue(outcome.facts().contains("事实B"));
    }

    @Test
    @DisplayName("最多两个：第三个即使命中也不执行，避免提示词被塞满")
    void capsAtTwoTools() {
        AiToolRouter router = new AiToolRouter(List.of(
                new FakeTool("A", true, "事实A", false),
                new FakeTool("B", true, "事实B", false),
                new FakeTool("C", true, "事实C", false)));
        AiToolOutcome outcome = router.route(1L, "问题");
        assertEquals(2, outcome.names().size());
        assertFalse(outcome.facts().contains("事实C"));
    }

    @Test
    @DisplayName("工具抛异常：跳过它，不影响其余工具与整轮问答")
    void skipsFailingTool() {
        AiToolRouter router = new AiToolRouter(List.of(
                new FakeTool("A", true, null, true),
                new FakeTool("B", true, "事实B", false)));
        AiToolOutcome outcome = router.route(1L, "问题");
        assertEquals(List.of("B"), outcome.names());
        assertTrue(outcome.facts().contains("事实B"));
    }

    @Test
    @DisplayName("未登录（userId=null）或没有命中：返回空结果，不查库")
    void returnsEmptyWhenNoUserOrNoMatch() {
        AiToolRouter hit = new AiToolRouter(List.of(new FakeTool("A", true, "事实A", false)));
        // 未登录：即使工具命中也不查库（避免匿名用户拿到任意人的数据）
        assertTrue(hit.route(null, "我的积分").names().isEmpty());
        assertFalse(hit.route(null, "我的积分").hasFacts());

        // 已登录但没有任何工具认领这个问题
        AiToolRouter miss = new AiToolRouter(List.of(new FakeTool("A", false, "事实A", false)));
        assertFalse(miss.route(1L, "你好").hasFacts());
    }

    @Test
    @DisplayName("MyRatingTool：能识别积分类问题，并把三类积分与胜率格式化成事实")
    void myRatingToolFormatsFacts() {
        User user = new User();
        user.setId(1L);
        user.setName("张三");
        user.setNickname("小张");
        user.setNameDisplay(1);
        user.setRating(1350);
        user.setRatingSingles(1300);
        user.setRatingDoubles(1400);
        user.setRatingMixed(1350);
        user.setGamesPlayed(10);
        user.setWinCount(6);
        user.setLossCount(4);
        user.setSinglesPlayed(4);
        user.setDoublesPlayed(4);
        user.setMixedPlayed(2);
        when(userMapper.selectById(1L)).thenReturn(user);
        // selectCount 返回包装类型，mock 默认 0 也可以，这里显式给 3 验证「约第 4 位」
        when(userMapper.selectCount(any())).thenReturn(3L);

        MyRatingTool tool = new MyRatingTool(userMapper);
        assertTrue(tool.supports("我的积分多少"));
        assertFalse(tool.supports("怎么报名球局"));

        String facts = tool.fetch(1L);
        assertTrue(facts.contains("小张"));
        assertTrue(facts.contains("1350"));
        assertTrue(facts.contains("约第 4 位"));
        assertTrue(facts.contains("60%"), "胜率 6/10 应显示 60%，实际=" + facts);
        assertTrue(facts.contains("混双 1350"));
    }
}
