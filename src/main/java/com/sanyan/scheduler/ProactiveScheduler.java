package com.sanyan.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.entity.AiCharacter;
import com.sanyan.entity.Conversation;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.repository.ConversationRepository;
import com.sanyan.service.ProactiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduler for AI proactive messages.
 * Three trigger strategies:
 * 1. Greeting — fixed time windows (morning / noon / evening)
 * 2. Event triggers — idle user detection
 * 3. Situational — random life-sharing moments
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveScheduler {

    private final AiCharacterRepository characterRepository;
    private final ConversationRepository conversationRepository;
    private final ProactiveService proactiveService;
    private final ObjectMapper objectMapper;

    /**
     * Every 10 minutes: check greeting time slots defined in each character's proactive_config.
     * Example config: {"active_hours":[8,22],"greeting":{"enabled":true,"slots":["08:00-09:00","12:00-13:00","21:00-22:00"]}}
     */
    @Scheduled(fixedRate = 600_000)
    public void greetingCheck() {
        LocalTime now = LocalTime.now();
        List<AiCharacter> characters = characterRepository.findAll();

        for (AiCharacter character : characters) {
            JsonNode cfg = parseConfig(character);
            if (cfg == null) continue;
            if (!isWithinActiveHours(now, cfg)) continue;

            JsonNode greetingNode = cfg.path("greeting");
            if (!greetingNode.path("enabled").asBoolean(true)) continue;
            JsonNode slots = greetingNode.path("slots");
            if (!slots.isArray() || slots.isEmpty()) continue;

            boolean inSlot = false;
            for (JsonNode slotNode : slots) {
                if (isInTimeSlot(now, slotNode.asText())) {
                    inSlot = true;
                    break;
                }
            }
            if (!inSlot) continue;

            List<Conversation> conversations = conversationRepository.findAll();
            for (Conversation conv : conversations) {
                if (!conv.getCharacterId().equals(character.getId())) continue;
                try {
                    proactiveService.sendProactiveMessage(conv, character,
                            "现在是打招呼的好时机，用温暖自然的方式主动问候用户，关心他/她的近况");
                } catch (Exception e) {
                    log.error("问候触发失败, convId={}", conv.getId(), e);
                }
            }
        }
    }

    /**
     * Every 30 minutes: detect idle users (no message in configurable hours) and send care message.
     * Example config: {"active_hours":[8,22],"event_trigger":{"enabled":true,"idle_hours_threshold":6}}
     */
    @Scheduled(fixedRate = 1_800_000)
    public void eventTriggerCheck() {
        LocalTime now = LocalTime.now();
        List<AiCharacter> characters = characterRepository.findAll();

        for (AiCharacter character : characters) {
            JsonNode cfg = parseConfig(character);
            if (cfg == null) continue;
            if (!isWithinActiveHours(now, cfg)) continue;

            JsonNode eventNode = cfg.path("event_trigger");
            if (!eventNode.path("enabled").asBoolean(true)) continue;
            int idleHours = eventNode.path("idle_hours_threshold").asInt(8);

            LocalDateTime threshold = LocalDateTime.now().minusHours(idleHours);
            List<Conversation> conversations = conversationRepository.findAll();

            for (Conversation conv : conversations) {
                if (!conv.getCharacterId().equals(character.getId())) continue;
                if (conv.getLastMessageAt() == null) continue;
                if (conv.getLastMessageAt().isAfter(threshold)) continue;

                try {
                    proactiveService.sendProactiveMessage(conv, character,
                            "用户已经有一段时间没有说话了，主动关心一下他/她，语气要自然温暖，不要显得刻意");
                } catch (Exception e) {
                    log.error("空闲触发失败, convId={}", conv.getId(), e);
                }
            }
        }
    }

    /**
     * Every hour: random situational trigger — share life moments, ask questions, etc.
     * Example config: {"active_hours":[8,22],"situational":{"enabled":true,"trigger_rate":0.2}}  (20% chance per check)
     */
    @Scheduled(fixedRate = 3_600_000)
    public void situationalCheck() {
        LocalTime now = LocalTime.now();
        List<AiCharacter> characters = characterRepository.findAll();

        for (AiCharacter character : characters) {
            JsonNode cfg = parseConfig(character);
            if (cfg == null) continue;
            if (!isWithinActiveHours(now, cfg)) continue;

            JsonNode situNode = cfg.path("situational");
            if (!situNode.path("enabled").asBoolean(true)) continue;
            double triggerRate = situNode.path("trigger_rate").asDouble(0.2);

            // Random probability check
            if (ThreadLocalRandom.current().nextDouble() > triggerRate) continue;

            List<Conversation> conversations = conversationRepository.findAll();
            for (Conversation conv : conversations) {
                if (!conv.getCharacterId().equals(character.getId())) continue;
                try {
                    proactiveService.sendProactiveMessage(conv, character,
                            "随机分享一个生活感悟、有趣的事情或者问用户一个轻松的问题，让对话更有温度");
                } catch (Exception e) {
                    log.error("情景触发失败, convId={}", conv.getId(), e);
                }
            }
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private JsonNode parseConfig(AiCharacter character) {
        if (character.getProactiveConfig() == null || character.getProactiveConfig().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(character.getProactiveConfig());
        } catch (Exception e) {
            log.warn("解析 proactive_config 失败, characterId={}", character.getId());
            return null;
        }
    }

    /**
     * Check if the current time is within the active hours range configured.
     * If active_hours not configured, returns true (no time restriction).
     *
     * Config example: {"active_hours": [8, 22]} means hours 8:00-22:59 inclusive.
     */
    static boolean isWithinActiveHours(LocalTime now, JsonNode cfg) {
        if (cfg == null || !cfg.has("active_hours")) return true;
        JsonNode range = cfg.get("active_hours");
        if (!range.isArray() || range.size() != 2) return true;
        int startHour = range.get(0).asInt();
        int endHour = range.get(1).asInt();
        int curHour = now.getHour();
        return curHour >= startHour && curHour <= endHour;
    }

    /**
     * Check if the given time is within a "HH:mm-HH:mm" time slot string.
     */
    private boolean isInTimeSlot(LocalTime now, String slot) {
        try {
            String[] parts = slot.split("-");
            if (parts.length != 2) return false;
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            return !now.isBefore(start) && !now.isAfter(end);
        } catch (Exception e) {
            log.warn("解析时间段失败: {}", slot);
            return false;
        }
    }
}
