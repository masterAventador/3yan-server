package com.sanyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private static final String TTS_URL = "https://openspeech.bytedance.com/api/v1/tts";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sanyan.tts.enabled:false}")
    private boolean enabled;

    @Value("${sanyan.tts.app-id:}")
    private String appId;

    @Value("${sanyan.tts.access-token:}")
    private String accessToken;

    @Value("${sanyan.tts.voice-type:zh_female_vv_uranus_bigtts}")
    private String voiceType;

    @Value("${sanyan.tts.cluster:volcano_tts}")
    private String cluster;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Synthesize speech, return MP3 audio bytes
     */
    public byte[] synthesize(String text, List<String> actions) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            String requestBody = buildRequestBody(appId, accessToken, cluster, voiceType, text, actions);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer;" + accessToken);

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            log.info("TTS 请求: textLength={}, actions={}", text.length(),
                    actions != null ? actions.size() : 0);
            long start = System.currentTimeMillis();

            ResponseEntity<String> response = restTemplate.exchange(
                    TTS_URL, HttpMethod.POST, entity, String.class);

            long elapsed = System.currentTimeMillis() - start;
            log.info("TTS 响应: status={}, 耗时={}ms", response.getStatusCode(), elapsed);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);
            String audioBase64 = (String) responseMap.get("data");
            if (audioBase64 == null) {
                log.error("TTS 返回无音频数据: {}", response.getBody());
                return null;
            }

            return Base64.getDecoder().decode(audioBase64);

        } catch (Exception e) {
            log.error("TTS 合成失败", e);
            return null;
        }
    }

    /**
     * Build TTS request body JSON
     */
    public static String buildRequestBody(String appId, String token, String cluster,
                                           String voiceType, String text,
                                           List<String> actions) {
        String finalText = text;

        // Map action descriptions to TTS emotion parameter
        String emotion = mapActionsToEmotion(actions);

        Map<String, Object> body = new LinkedHashMap<>();

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("appid", appId);
        app.put("token", token);
        app.put("cluster", cluster);
        body.put("app", app);

        Map<String, String> user = Map.of("uid", "sanyan_server");
        body.put("user", user);

        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("voice_type", voiceType);
        audio.put("encoding", "mp3");
        audio.put("speed_ratio", 1.0);
        audio.put("volume_ratio", 1.0);
        audio.put("pitch_ratio", 1.0);
        if (emotion != null) {
            audio.put("emotion", emotion);
            audio.put("emotion_strength", 0.8);
        }
        body.put("audio", audio);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reqid", UUID.randomUUID().toString());
        request.put("text", finalText);
        request.put("text_type", "plain");
        request.put("operation", "query");
        body.put("request", request);

        try {
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("构建 TTS 请求体失败", e);
        }
    }

    /**
     * Map action descriptions to TTS emotion value.
     * Returns null if no emotion can be determined.
     */
    public static String mapActionsToEmotion(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        String combined = String.join(" ", actions);

        // Happy signals
        if (combined.contains("开心") || combined.contains("笑") || combined.contains("高兴")
                || combined.contains("拍手") || combined.contains("跳") || combined.contains("兴奋")
                || combined.contains("欢呼") || combined.contains("雀跃")) {
            return "happy";
        }
        // Sad signals
        if (combined.contains("难过") || combined.contains("哭") || combined.contains("伤心")
                || combined.contains("叹气") || combined.contains("低落") || combined.contains("流泪")
                || combined.contains("委屈")) {
            return "sad";
        }
        // Angry signals
        if (combined.contains("生气") || combined.contains("愤怒") || combined.contains("皱眉")
                || combined.contains("气鼓鼓") || combined.contains("瞪") || combined.contains("怒")) {
            return "angry";
        }
        // Default: no specific emotion, let TTS decide naturally
        return null;
    }
}
