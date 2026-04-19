package com.sanyan.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "message", indexes = {
    @Index(name = "idx_message_conversation", columnList = "conversationId,id")
})
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long conversationId;
    @Column(nullable = false, length = 10)
    private String senderType; // user / ai
    @Column(nullable = false, length = 10)
    private String contentType; // text / voice
    @Column(columnDefinition = "TEXT")
    private String content;
    private String mediaUrl;
    @Column
    private Integer duration;
    @Column(nullable = false, length = 10)
    private String source; // reply / proactive
    // AI 语音消息对应的 TTS 风格指令（[tts_style:...] 标签里的自然语言）。
    // 下一轮 AI 对话时拼回历史消息末尾，用于维持情绪连贯性。
    @Column(length = 500)
    private String ttsStyle;
    // 降级原因：asr_failed（ASR 识别失败）、tts_failed（TTS 合成失败）、null（正常）。
    // 客户端可据此展示"由于网络问题采用文字回复"等提示。
    @Column(length = 32)
    private String fallbackReason;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
