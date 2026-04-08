package com.sanyan.service;

import com.sanyan.entity.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.sanyan.repository.MemoryProfileRepository;
import com.sanyan.repository.MemorySummaryRepository;
import com.sanyan.repository.MessageRepository;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock private MemoryProfileRepository profileRepository;
    @Mock private MemorySummaryRepository summaryRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private AiService aiService;
    @InjectMocks private MemoryService memoryService;

    @Test
    void shouldBuildProfileUpdatePrompt() {
        String currentProfile = "小明，程序员，喜欢打篮球";
        java.util.List<Message> messages = java.util.List.of(
                createMessage("user", "我最近换工作了，去做设计了"),
                createMessage("ai", "哇，那挺大的转变啊")
        );

        String prompt = memoryService.buildProfileUpdatePrompt(currentProfile, messages);
        assertThat(prompt).contains("小明");
        assertThat(prompt).contains("换工作");
    }

    @Test
    void shouldBuildSummaryPrompt() {
        java.util.List<Message> messages = java.util.List.of(
                createMessage("user", "今天面试了一家公司"),
                createMessage("ai", "怎么样？顺利吗？"),
                createMessage("user", "还行，等通知吧")
        );

        String prompt = memoryService.buildSummaryPrompt(messages);
        assertThat(prompt).contains("面试");
    }

    private Message createMessage(String senderType, String content) {
        Message m = new Message();
        m.setSenderType(senderType);
        m.setContent(content);
        return m;
    }
}
