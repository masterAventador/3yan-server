package com.sanyan.character.internal.intimacy.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 调 LLM（BACKGROUND task 即成本优先模型）评估对话质量，输出 0-20 分。
 *
 * <p>触发频率：每 {@code IntimacyProperties.Ai#triggerEveryNMessages} 条用户消息一次
 * （由 MessagePersistedListener 控制，本 evaluator 只负责打分）。
 *
 * <p>失败兜底：LLM 返回非 JSON / 解析失败 → 返回 (0, "评估失败")，不阻断主流程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationQualityEvaluator {

    private static final String SYSTEM_PROMPT = """
            评估以下用户与小婉的对话片段质量（0-20 分）。
            评分维度：
            - 用户分享深度（透露真实信息、情感、想法）
            - 对话连贯性（不是单字"嗯"/"哦"敷衍）
            - 互动深度（双向，不是单方面输出）
            返回 JSON：{"score": <0-20>, "reason": "<10字内>"}
            只返回 JSON，不要额外解释。
            """;

    private final LlmApi llmApi;
    private final ObjectMapper mapper;
    private final IntimacyProperties props;

    public QualityScoreResponse score(List<MessageDto> recentMessages) {
        String convo = recentMessages.stream()
                .map(m -> {
                    String role = SenderType.USER.equalsIgnoreCase(m.senderType()) ? "用户" : "小婉";
                    return role + "：" + (m.content() == null ? "" : m.content());
                })
                .collect(Collectors.joining("\n"));

        List<ChatMessage> messages = List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(convo)
        );

        try {
            String raw = llmApi.chat(LlmTaskType.BACKGROUND, messages);
            QualityScoreResponse resp = mapper.readValue(raw, QualityScoreResponse.class);
            int capped = Math.max(0, Math.min(resp.score(), props.getAi().getQualityMaxScore()));
            return new QualityScoreResponse(capped, resp.reason());
        } catch (Exception e) {
            log.warn("对话质量评估失败，降级为 0 分", e);
            return new QualityScoreResponse(0, "评估失败");
        }
    }
}
