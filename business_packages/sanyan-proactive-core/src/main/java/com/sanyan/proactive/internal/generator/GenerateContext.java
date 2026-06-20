package com.sanyan.proactive.internal.generator;

import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.memory.dto.MemoryContext;

import java.util.List;
import java.util.Map;

/**
 * 生成器输入上下文（spec §5.4）。
 *
 * @param relationship            character-api 返回的关系（含 currentStage / currentStageName）
 * @param stagePromptSegment      character-api.getStagePromptSegment 拼好的 stage 称呼/语调片段
 * @param memoryContext           memory-api.getRelevantContext 整合的长期记忆（可能为 null）
 * @param payload                 事件 payload 解析出的键值（如 escalationLevel / memoryItemId）
 * @param recentProactiveMessages 最近已主动发过的消息文本（反重复用，可空 list）：喂回 prompt
 *                                让 LLM 别复读同义的早晚安/想你/熬夜话题
 */
public record GenerateContext(
        Long userId,
        Long characterId,
        RelationshipDto relationship,
        String stagePromptSegment,
        MemoryContext memoryContext,
        Map<String, Object> payload,
        List<String> recentProactiveMessages) {}
