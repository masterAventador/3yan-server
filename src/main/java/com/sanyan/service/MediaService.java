package com.sanyan.service;

import com.sanyan.dto.data.MediaUploadData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final long MAX_VOICE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MIN_DURATION = 1;
    private static final int MAX_DURATION = 60;

    /**
     * 支持的语音格式及其对应的 COS content-type。
     * 客户端通过 MultipartFile filename 的后缀告诉服务端格式。
     */
    private static final Map<String, String> SUPPORTED_AUDIO_FORMATS = Map.of(
        "wav",  "audio/wav",
        "m4a",  "audio/mp4",
        "mp3",  "audio/mpeg",
        "aac",  "audio/aac",
        "opus", "audio/opus"
    );

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

        String ext = extractExtension(file.getOriginalFilename());
        String contentType = SUPPORTED_AUDIO_FORMATS.get(ext);
        if (contentType == null) {
            throw new IllegalArgumentException("不支持的音频格式: " + ext
                + "（支持 " + String.join("/", SUPPORTED_AUDIO_FORMATS.keySet()) + "）");
        }

        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String key = CosService.buildUserVoiceKey(userId, uuid, ext);
        try {
            String url = cosService.upload(file.getBytes(), key, contentType);
            log.info("用户语音上传成功: userId={}, key={}, duration={}s, format={}", userId, key, duration, ext);
            return new MediaUploadData(url, duration);
        } catch (Exception e) {
            log.error("用户语音上传失败: userId={}", userId, e);
            throw new RuntimeException("语音上传失败", e);
        }
    }

    /**
     * 从 filename 提取后缀并转小写。null / 无后缀 / 纯点都返回空串，由白名单拦住。
     */
    private static String extractExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) return "";
        return filename.substring(dotIndex + 1).toLowerCase();
    }
}
