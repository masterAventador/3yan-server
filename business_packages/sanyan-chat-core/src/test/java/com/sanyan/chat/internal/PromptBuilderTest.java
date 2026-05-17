package com.sanyan.chat.internal;

import com.sanyan.chat.SenderType;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.dto.MemoryContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2 Task Q3：{@link PromptBuilder} 单元测试。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>characterPrompt 作为第一条 system 消息</li>
 *   <li>memoryContext 非空时作为第二条 system 消息（含「她对你的记忆：」前缀）</li>
 *   <li>memoryContext 为 null / EMPTY 时跳过该 system 段</li>
 *   <li>消息历史按 SenderType 映射为 user / assistant role</li>
 *   <li>消息历史最多 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条（= 32）</li>
 *   <li>传入超过 32 条时只取最近 32 条（按列表尾部）</li>
 *   <li>null content 兜底为空串（防止 NPE）</li>
 * </ul>
 */
class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    void build_shouldEmitCharacterPromptAsFirstSystemMessage() {
        List<Map<String, String>> result =
                builder.build("你是小婉", null, List.of());

        assertThat(result).isNotEmpty();
        assertThat(result.get(0))
                .containsEntry("role", "system")
                .containsEntry("content", "你是小婉");
    }

    @Test
    void build_shouldEmitMemoryContextAsSecondSystemMessageWhenPresent() {
        MemoryContext ctx = new MemoryContext("用户喜欢猫\n上次聊到 RAG");

        List<Map<String, String>> result =
                builder.build("你是小婉", ctx, List.of());

        assertThat(result).hasSize(2);
        assertThat(result.get(1))
                .containsEntry("role", "system");
        assertThat(result.get(1).get("content"))
                .startsWith("她对你的记忆：")
                .contains("用户喜欢猫")
                .contains("上次聊到 RAG");
    }

    @Test
    void build_shouldSkipMemoryContextWhenNull() {
        List<Map<String, String>> result =
                builder.build("你是小婉", null, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("role", "system");
    }

    @Test
    void build_shouldSkipMemoryContextWhenEmpty() {
        List<Map<String, String>> result =
                builder.build("你是小婉", MemoryContext.EMPTY, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("role", "system");
    }

    @Test
    void build_shouldSkipMemoryContextWhenBlankText() {
        MemoryContext ctx = new MemoryContext("   \n  ");

        List<Map<String, String>> result =
                builder.build("你是小婉", ctx, List.of());

        assertThat(result).hasSize(1);
    }

    @Test
    void build_shouldMapUserSenderTypeToUserRole() {
        MessageEntity msg = userMessage("早上好");

        List<Map<String, String>> result =
                builder.build("sys", null, List.of(msg));

        assertThat(result).hasSize(2);
        assertThat(result.get(1))
                .containsEntry("role", "user")
                .containsEntry("content", "早上好");
    }

    @Test
    void build_shouldMapAiSenderTypeToAssistantRole() {
        MessageEntity msg = aiMessage("早上好呀");

        List<Map<String, String>> result =
                builder.build("sys", null, List.of(msg));

        assertThat(result).hasSize(2);
        assertThat(result.get(1))
                .containsEntry("role", "assistant")
                .containsEntry("content", "早上好呀");
    }

    @Test
    void build_shouldFallbackNullContentToEmptyString() {
        MessageEntity msg = new MessageEntity();
        msg.setSenderType(SenderType.USER);
        msg.setContent(null);

        List<Map<String, String>> result =
                builder.build("sys", null, List.of(msg));

        assertThat(result).hasSize(2);
        assertThat(result.get(1)).containsEntry("content", "");
    }

    @Test
    void build_shouldEmitFullPromptStructureWithCharacterAndMemoryAndHistory() {
        MemoryContext ctx = new MemoryContext("用户最近聊到工作");
        MessageEntity user = userMessage("今天工作累");
        MessageEntity ai = aiMessage("辛苦了");

        List<Map<String, String>> result =
                builder.build("你是小婉", ctx, List.of(user, ai));

        assertThat(result).hasSize(4);
        assertThat(result.get(0)).containsEntry("role", "system").containsEntry("content", "你是小婉");
        assertThat(result.get(1)).containsEntry("role", "system");
        assertThat(result.get(1).get("content")).startsWith("她对你的记忆：");
        assertThat(result.get(2)).containsEntry("role", "user").containsEntry("content", "今天工作累");
        assertThat(result.get(3)).containsEntry("role", "assistant").containsEntry("content", "辛苦了");
    }

    @Test
    void build_shouldIncludeAllMessagesWhenExactlyAtWindowSize() {
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < MemoryConstants.SHORT_TERM_WINDOW_SIZE; i++) {
            messages.add(userMessage("m" + i));
        }

        List<Map<String, String>> result =
                builder.build("sys", null, messages);

        // 1 system + 32 history = 33
        assertThat(result).hasSize(1 + MemoryConstants.SHORT_TERM_WINDOW_SIZE);
        assertThat(result.get(1)).containsEntry("content", "m0");
        assertThat(result.get(MemoryConstants.SHORT_TERM_WINDOW_SIZE))
                .containsEntry("content", "m" + (MemoryConstants.SHORT_TERM_WINDOW_SIZE - 1));
    }

    @Test
    void build_shouldKeepOnlyLastWindowSizeMessagesWhenExceedingLimit() {
        // 传入 33 条，只取最近 32 条（即丢弃最早 1 条）
        int extra = 1;
        int total = MemoryConstants.SHORT_TERM_WINDOW_SIZE + extra;
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            messages.add(userMessage("m" + i));
        }

        List<Map<String, String>> result =
                builder.build("sys", null, messages);

        // 1 system + 32 history = 33
        assertThat(result).hasSize(1 + MemoryConstants.SHORT_TERM_WINDOW_SIZE);
        // 第 1 条（index 1）应该是 m1（丢弃了 m0）
        assertThat(result.get(1)).containsEntry("content", "m" + extra);
        // 最后一条仍是 m32（即 total - 1）
        assertThat(result.get(MemoryConstants.SHORT_TERM_WINDOW_SIZE))
                .containsEntry("content", "m" + (total - 1));
    }

    @Test
    void build_shouldTolerateNullMessagesList() {
        List<Map<String, String>> result =
                builder.build("sys", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("role", "system");
    }

    private static MessageEntity userMessage(String content) {
        MessageEntity m = new MessageEntity();
        m.setSenderType(SenderType.USER);
        m.setContent(content);
        return m;
    }

    private static MessageEntity aiMessage(String content) {
        MessageEntity m = new MessageEntity();
        m.setSenderType(SenderType.AI);
        m.setContent(content);
        return m;
    }
}
