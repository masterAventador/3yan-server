package com.sanyan.proactive.internal.generator;

import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.dto.MemoryContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProactivePromptBuilderTest {

    private final ProactivePromptBuilder builder = new ProactivePromptBuilder(java.time.Clock.systemDefaultZone());

    private GenerateContext ctx(String stageSegment, MemoryContext mem, Map<String, Object> payload) {
        RelationshipDto rel = new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5);
        return new GenerateContext(1L, 1L, rel, stageSegment, mem, payload, List.of());
    }

    @Test
    void build_should_put_persona_then_stage_then_memory_in_system_then_scene_instruction_in_user() {
        List<ChatMessage> messages = builder.build(
                ctx("当前关系阶段：朋友。称呼用户用：你。语调：自然。",
                        new MemoryContext("他喜欢喝美式咖啡。"),
                        Map.of()),
                "现在请你主动跟他说一句早安。");

        // 至少一条 system + 一条 user
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        // system 段同时含人设基底、stage、记忆三块
        String system = messages.get(0).content();
        assertThat(system).contains("小婉");                  // 基底人设占位
        assertThat(system).contains("当前关系阶段：朋友");      // stage segment
        assertThat(system).contains("他喜欢喝美式咖啡");        // memoryContext.text()
        // 最后一条 user 段是场景指令
        ChatMessage last = messages.get(messages.size() - 1);
        assertThat(last.role()).isEqualTo("user");
        assertThat(last.content()).isEqualTo("现在请你主动跟他说一句早安。");
    }

    @Test
    void build_should_skip_blank_stage_and_null_memory() {
        List<ChatMessage> messages = builder.build(
                ctx("", null, Map.of()),
                "现在说句话。");

        String system = messages.get(0).content();
        assertThat(system).contains("小婉");
        assertThat(system).doesNotContain("当前关系阶段");
        // 不应出现记忆前缀
        assertThat(system).doesNotContain("她记得");
    }

    @Test
    void build_should_inject_current_time_into_system() {
        // 固定时钟：2026-06-17 22:30 (UTC+8)，周三晚上
        java.time.Clock fixed = java.time.Clock.fixed(
                java.time.Instant.parse("2026-06-17T14:30:00Z"), java.time.ZoneId.of("Asia/Shanghai"));
        ProactivePromptBuilder b = new ProactivePromptBuilder(fixed);

        List<ChatMessage> messages = b.build(ctx("", null, Map.of()), "说句话。");
        String system = messages.get(0).content();
        assertThat(system).contains("[当前时间]");
        assertThat(system).contains("2026年6月17日");
        assertThat(system).contains("晚上十点半");
    }

    @Test
    void build_should_inject_recent_proactive_and_antirepeat_when_present() {
        GenerateContext c = new GenerateContext(1L, 1L,
                new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5),
                "", null, Map.of(),
                List.of("早安呀", "睡了吗笨蛋"));   // 新字段：最近推过的
        List<ChatMessage> messages = builder.build(c, "再发一句。");
        String system = messages.get(0).content();
        assertThat(system).contains("最近你已经主动发过");
        assertThat(system).contains("早安呀");
        assertThat(system).contains("睡了吗笨蛋");
        assertThat(system).contains("不要重复");
    }

    @Test
    void build_should_skip_null_or_blank_recent_proactive_elements() {
        // MessageEntity.content 列允许 null，list 可能含 null / 空白；不应 NPE，应跳过这些元素
        GenerateContext c = new GenerateContext(1L, 1L,
                new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5),
                "", null, Map.of(),
                java.util.Arrays.asList("早安呀", null, "  "));
        String system = builder.build(c, "再发一句。").get(0).content();
        assertThat(system).contains("早安呀");
        assertThat(system).doesNotContain("null");
    }

    @Test
    void build_should_skip_antirepeat_when_recent_empty() {
        GenerateContext c = new GenerateContext(1L, 1L,
                new RelationshipDto(1L, 1L, 250, 1, "朋友", 300, 0.5),
                "", null, Map.of(), List.of());
        String system = builder.build(c, "说句话。").get(0).content();
        assertThat(system).doesNotContain("最近你已经主动发过");
    }

    @Test
    void persona_should_constrain_nickname_frequency() {
        List<ChatMessage> messages = builder.build(ctx("", null, Map.of()), "说句话。");
        String system = messages.get(0).content();
        // 约束关键词：大多数消息不要以称呼开头
        assertThat(system).contains("称呼");
        assertThat(system).contains("不要每");
    }
}
