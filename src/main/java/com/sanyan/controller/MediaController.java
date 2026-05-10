package com.sanyan.controller;

import com.sanyan.auth.LoginUser;
import com.sanyan.dto.ApiResponse;
import com.sanyan.dto.data.MediaUploadData;
import com.sanyan.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @PostMapping("/upload")
    public ApiResponse<MediaUploadData> upload(
            @LoginUser Long userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam("duration") Integer duration) {

        if (!"voice".equals(type)) {
            return ApiResponse.fail("不支持的媒体类型: " + type);
        }

        try {
            MediaUploadData data = mediaService.uploadVoice(userId, file, duration);
            return ApiResponse.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            log.error("媒体上传失败", e);
            return ApiResponse.fail("上传失败");
        }
    }
}
