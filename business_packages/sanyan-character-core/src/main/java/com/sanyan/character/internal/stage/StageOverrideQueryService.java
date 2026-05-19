package com.sanyan.character.internal.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.character.internal.AiCharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 从 character.persona_config.stage_overrides[stage] 读 address + tone_hint，
 * 拼成 prompt 片段供 chat-core 注入 system message。
 *
 * <p>如果 character 不存在、persona_config 为空、stage_overrides 没该 stage key、
 * 或 JSON 解析失败，都返回空字符串（调用方据此跳过这段 system message）。
 */
@Service
@RequiredArgsConstructor
public class StageOverrideQueryService {

    private final AiCharacterRepository charRepo;
    private final StageDefinition stageDef;
    private final ObjectMapper mapper;

    public String buildSegment(Long characterId, int stage) {
        AiCharacterEntity ch = charRepo.findById(characterId).orElse(null);
        if (ch == null || ch.getPersonaConfig() == null || ch.getPersonaConfig().isBlank()) {
            return "";
        }

        try {
            JsonNode root = ch.getPersonaConfig().startsWith("{")
                    ? mapper.readTree(ch.getPersonaConfig())
                    : mapper.createObjectNode();
            JsonNode override = root.path("stage_overrides").path(String.valueOf(stage));
            if (override.isMissingNode() || override.isEmpty()) {
                return "";
            }

            String address  = override.path("address").asText("");
            String toneHint = override.path("tone_hint").asText("");

            return String.format("当前关系阶段：%s。称呼用户用：%s。语调：%s。",
                    stageDef.nameOf(stage), address, toneHint);
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
