package com.sanyan.llm.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.chat.internal.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock RestTemplate restTemplate;

    @Test
    void chat_shouldSendSystemPromptLoadedFromResource() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AiService service = new AiService(messageRepository, objectMapper, restTemplate);
        Resource resource = new ByteArrayResource(
                "MARKER_FROM_RESOURCE_FILE".getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(service, "systemPromptResource", resource);
        ReflectionTestUtils.invokeMethod(service, "loadSystemPrompt");

        when(messageRepository.findByUserIdOrderByIdDesc(anyLong(), any()))
                .thenReturn(Collections.emptyList());
        when(restTemplate.exchange(nullable(String.class), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        HttpStatus.OK));

        AiCharacterEntity character = new AiCharacterEntity();
        character.setName("小婉");
        service.chat(character, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(nullable(String.class), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        String body = captor.getValue().getBody();
        assertThat(body)
                .as("system prompt 必须来自资源文件，而不是 character 实体字段")
                .contains("MARKER_FROM_RESOURCE_FILE");
    }

    @Test
    void productionSystemPromptResource_containsRelationshipBoundaries() throws IOException {
        String prompt = Files.readString(
                Path.of("src/main/resources/prompts/xiaowan-system.md"));
        assertThat(prompt)
                .as("人设资源文件必须包含小婉介绍与三类边界")
                .contains("你是小婉")
                .contains("[能力边界]")
                .contains("[关系边界]")
                .contains("纯线上聊天")
                .contains("线下");
    }
}
