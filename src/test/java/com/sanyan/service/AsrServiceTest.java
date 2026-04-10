package com.sanyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsrServiceTest {

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private AsrService asrService;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        asrService = new AsrService(restTemplate, objectMapper);
        ReflectionTestUtils.setField(asrService, "enabled", true);
        ReflectionTestUtils.setField(asrService, "appId", "6383804695");
        ReflectionTestUtils.setField(asrService, "accessToken", "test-token");
    }

    @Test
    void transcribe_success_returnsText() {
        HttpHeaders respHeaders = new HttpHeaders();
        respHeaders.add("X-Api-Status-Code", "20000000");
        String body = "{\"result\":{\"text\":\"今天心情不好\"}}";
        ResponseEntity<String> response = new ResponseEntity<>(body, respHeaders, HttpStatus.OK);

        when(restTemplate.exchange(
                eq("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(response);

        String result = asrService.transcribe("https://example.com/audio.m4a");

        assertThat(result).isEqualTo("今天心情不好");
    }

    @Test
    void transcribe_silenceAudio_returnsEmptyString() {
        HttpHeaders respHeaders = new HttpHeaders();
        respHeaders.add("X-Api-Status-Code", "20000003");
        ResponseEntity<String> response = new ResponseEntity<>("{}", respHeaders, HttpStatus.OK);

        when(restTemplate.exchange(
                eq("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(response);

        String result = asrService.transcribe("https://example.com/audio.m4a");

        assertThat(result).isEmpty();
    }

    @Test
    void transcribe_errorCode_retriesOnceThenReturnsNull() {
        HttpHeaders respHeaders = new HttpHeaders();
        respHeaders.add("X-Api-Status-Code", "45000151");
        ResponseEntity<String> response = new ResponseEntity<>("{}", respHeaders, HttpStatus.OK);

        when(restTemplate.exchange(
                eq("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(response);

        String result = asrService.transcribe("https://example.com/audio.m4a");

        assertThat(result).isNull();
        verify(restTemplate, times(2)).exchange(
                any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void transcribe_networkException_retriesOnceThenReturnsNull() {
        when(restTemplate.exchange(
                eq("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenThrow(new RestClientException("network error"));

        String result = asrService.transcribe("https://example.com/audio.m4a");

        assertThat(result).isNull();
        verify(restTemplate, times(2)).exchange(
                any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void transcribe_firstCallFailsSecondSucceeds_returnsText() {
        HttpHeaders successHeaders = new HttpHeaders();
        successHeaders.add("X-Api-Status-Code", "20000000");
        ResponseEntity<String> successResponse = new ResponseEntity<>(
                "{\"result\":{\"text\":\"重试后成功\"}}",
                successHeaders,
                HttpStatus.OK);

        when(restTemplate.exchange(
                eq("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        ))
                .thenThrow(new RestClientException("first call fails"))
                .thenReturn(successResponse);

        String result = asrService.transcribe("https://example.com/audio.m4a");

        assertThat(result).isEqualTo("重试后成功");
    }

    @Test
    void isEnabled_returnsConfigValue() {
        assertThat(asrService.isEnabled()).isTrue();

        ReflectionTestUtils.setField(asrService, "enabled", false);
        assertThat(asrService.isEnabled()).isFalse();
    }
}
