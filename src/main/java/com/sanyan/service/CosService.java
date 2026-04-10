package com.sanyan.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

@Slf4j
@Service
public class CosService {

    @Value("${sanyan.cos.secret-id}")
    private String secretId;

    @Value("${sanyan.cos.secret-key}")
    private String secretKey;

    @Value("${sanyan.cos.bucket}")
    private String bucket;

    @Value("${sanyan.cos.region}")
    private String region;

    private COSClient cosClient;

    @PostConstruct
    public void init() {
        var cred = new BasicCOSCredentials(secretId, secretKey);
        var config = new ClientConfig(new Region(region));
        cosClient = new COSClient(cred, config);
        log.info("COS 客户端初始化完成: bucket={}, region={}", bucket, region);
    }

    @PreDestroy
    public void destroy() {
        if (cosClient != null) {
            cosClient.shutdown();
            log.info("COS 客户端已关闭");
        }
    }

    public String upload(byte[] data, String key, String contentType) {
        try {
            var metadata = new ObjectMetadata();
            metadata.setContentLength(data.length);
            metadata.setContentType(contentType);
            cosClient.putObject(bucket, key, new ByteArrayInputStream(data), metadata);
            String url = buildCosUrl(bucket, region, key);
            log.info("文件上传 COS 成功: key={}, size={}bytes, url={}", key, data.length, url);
            return url;
        } catch (Exception e) {
            log.error("COS 上传失败: key={}", key, e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    public static String buildAiVoiceKey(Long conversationId, Long messageId) {
        return "voice/ai/" + conversationId + "/" + messageId + ".mp3";
    }

    public static String buildUserVoiceKey(Long userId, String uuid) {
        return "voice/user/" + userId + "/" + System.currentTimeMillis() + "_" + uuid + ".m4a";
    }

    public static String buildCosUrl(String bucket, String region, String key) {
        return "https://" + bucket + ".cos." + region + ".myqcloud.com/" + key;
    }
}
