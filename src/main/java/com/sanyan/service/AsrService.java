package com.sanyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 豆包大模型录音文件识别极速版（volc.bigasr.auc_turbo）调用。
 * 一次 HTTP 请求返回识别结果，不需要轮询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrService {

    private static final String ASR_URL =
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash";
    private static final String RESOURCE_ID = "volc.bigasr.auc_turbo";

    private static final String CODE_SUCCESS = "20000000";
    private static final String CODE_SILENCE = "20000003";

    private static final int MAX_ATTEMPTS = 2;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sanyan.asr.enabled:false}")
    private boolean enabled;

    @Value("${sanyan.asr.app-id:}")
    private String appId;

    @Value("${sanyan.asr.access-token:}")
    private String accessToken;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Transcribe audio URL to text.
     *
     * @param audioUrl publicly accessible audio URL
     * @return transcribed text; empty string for silence; null on failure
     */
    public String transcribe(String audioUrl) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String result = doTranscribe(audioUrl);
                if (result != null) {
                    return result;
                }
                log.warn("ASR 调用返回空结果，attempt={}", attempt);
            } catch (Exception e) {
                log.warn("ASR 调用异常, attempt={}", attempt, e);
            }
        }
        return null;
    }

    /**
     * Execute one ASR request.
     *
     * @return transcribed text; empty string for silence; null on failed status code
     */
    private String doTranscribe(String audioUrl) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-App-Key", appId);
        headers.set("X-Api-Access-Key", accessToken);
        headers.set("X-Api-Resource-Id", RESOURCE_ID);
        headers.set("X-Api-Request-Id", UUID.randomUUID().toString());
        headers.set("X-Api-Sequence", "-1");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", Map.of("uid", "sanyan_server"));
        body.put("audio", Map.of("url", audioUrl));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model_name", "bigmodel");
        request.put("enable_itn", true);
        request.put("enable_punc", true);
        body.put("request", request);

        String requestBody = objectMapper.writeValueAsString(body);
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        log.info("ASR 请求: audioUrl={}", audioUrl);
        long start = System.currentTimeMillis();

        ResponseEntity<String> response = restTemplate.exchange(
                ASR_URL, HttpMethod.POST, entity, String.class);

        long elapsed = System.currentTimeMillis() - start;
        String statusCode = response.getHeaders().getFirst("X-Api-Status-Code");
        String logId = response.getHeaders().getFirst("X-Tt-Logid");
        log.info("ASR 响应: statusCode={}, logId={}, 耗时={}ms", statusCode, logId, elapsed);

        if (CODE_SUCCESS.equals(statusCode)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap =
                    objectMapper.readValue(response.getBody(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) responseMap.get("result");
            if (result == null) {
                log.error("ASR 响应缺少 result 字段: {}", response.getBody());
                return null;
            }
            String text = (String) result.get("text");
            return text != null ? text : "";
        }

        if (CODE_SILENCE.equals(statusCode)) {
            log.info("ASR 识别到静音音频");
            return "";
        }

        log.error("ASR 返回错误状态码: {}", statusCode);
        return null;
    }
}
