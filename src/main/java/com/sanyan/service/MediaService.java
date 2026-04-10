package com.sanyan.service;

import com.sanyan.dto.data.MediaUploadData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final long MAX_VOICE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MIN_DURATION = 1;
    private static final int MAX_DURATION = 60;

    private final CosService cosService;

    public MediaUploadData uploadVoice(Long userId, MultipartFile file, Integer duration) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > MAX_VOICE_SIZE) {
            throw new IllegalArgumentException("文件过大，最大 5MB");
        }
        if (duration == null || duration < MIN_DURATION || duration > MAX_DURATION) {
            throw new IllegalArgumentException("时长必须在 " + MIN_DURATION + "-" + MAX_DURATION + " 秒之间");
        }

        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String key = CosService.buildUserVoiceKey(userId, uuid);
        try {
            String url = cosService.upload(file.getBytes(), key, "audio/mp4");
            log.info("用户语音上传成功: userId={}, key={}, duration={}s", userId, key, duration);
            return new MediaUploadData(url, duration);
        } catch (Exception e) {
            log.error("用户语音上传失败: userId={}", userId, e);
            throw new RuntimeException("语音上传失败", e);
        }
    }
}
