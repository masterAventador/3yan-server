package com.sanyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.util.TextProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private static final String TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";

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

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Synthesize speech using V3 HTTP Chunked API.
     * Returns MP3 audio bytes, or null on failure.
     */
    public byte[] synthesize(String text, TextProcessor.EmotionTag emotion, List<String> contextTexts) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            String requestBody = buildRequestBody(voiceType, text, emotion, contextTexts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Api-App-Id", appId);
            headers.set("X-Api-Access-Key", accessToken);
            headers.set("X-Api-Resource-Id", "seed-tts-2.0");

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            log.info("TTS V3 请求: textLength={}, emotion={}, contextTexts={}",
                    text.length(),
                    emotion != null ? emotion.type() + ":" + emotion.scale() : "none",
                    contextTexts != null ? contextTexts.size() : 0);
            long start = System.currentTimeMillis();

            ResponseEntity<String> response = restTemplate.exchange(
                    TTS_URL, HttpMethod.POST, entity, String.class);

            long elapsed = System.currentTimeMillis() - start;
            log.info("TTS V3 响应: status={}, 耗时={}ms", response.getStatusCode(), elapsed);

            // V3 response is chunked: multiple JSON lines, collect all base64 data
            return parseChunkedResponse(response.getBody());

        } catch (Exception e) {
            log.error("TTS V3 合成失败", e);
            return null;
        }
    }

    /**
     * Parse V3 chunked response: multiple JSON objects, concatenate base64 audio data.
     */
    private byte[] parseChunkedResponse(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        ByteArrayOutputStream audioStream = new ByteArrayOutputStream();

        // Response contains multiple JSON objects, one per line
        for (String line : responseBody.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> chunk = objectMapper.readValue(trimmed, Map.class);

            int code = chunk.get("code") instanceof Number ? ((Number) chunk.get("code")).intValue() : 0;

            // code 20000000 = session finished successfully
            if (code == 20000000) {
                log.info("TTS V3 合成完成");
                break;
            }

            // code != 0 and not success = error
            if (code != 0) {
                String message = (String) chunk.get("message");
                log.error("TTS V3 错误: code={}, message={}", code, message);
                return null;
            }

            // Collect base64 audio data
            String audioBase64 = (String) chunk.get("data");
            if (audioBase64 != null && !audioBase64.isEmpty()) {
                audioStream.write(Base64.getDecoder().decode(audioBase64));
            }
        }

        byte[] result = audioStream.toByteArray();
        if (result.length == 0) {
            log.error("TTS V3 返回无音频数据");
            return null;
        }

        log.info("TTS V3 音频拼接完成: size={}bytes", result.length);
        return result;
    }

    /**
     * Build V3 TTS request body JSON.
     */
    public static String buildRequestBody(String speaker, String text,
                                           TextProcessor.EmotionTag emotion,
                                           List<String> contextTexts) {
        Map<String, Object> body = new LinkedHashMap<>();

        // user
        body.put("user", Map.of("uid", "sanyan_server"));

        // req_params
        Map<String, Object> reqParams = new LinkedHashMap<>();
        reqParams.put("text", text);
        reqParams.put("speaker", speaker);

        // audio_params
        Map<String, Object> audioParams = new LinkedHashMap<>();
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);
        audioParams.put("loudness_rate", 50); // 提高音频响度（范围 -50 ~ 100）
        if (emotion != null) {
            audioParams.put("emotion", emotion.type());
            audioParams.put("emotion_scale", emotion.scale());
        }
        reqParams.put("audio_params", audioParams);

        // additions must be a JSON string, not an object
        if (contextTexts != null && !contextTexts.isEmpty()) {
            try {
                Map<String, Object> additionsMap = new LinkedHashMap<>();
                additionsMap.put("context_texts", contextTexts);
                reqParams.put("additions", new ObjectMapper().writeValueAsString(additionsMap));
            } catch (Exception ignored) {}
        }

        body.put("req_params", reqParams);

        try {
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("构建 TTS V3 请求体失败", e);
        }
    }
}
