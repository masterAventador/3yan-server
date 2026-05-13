package com.sanyan.common.storage;

import java.io.InputStream;

/**
 * 对象存储抽象。具体实现按云服务商选（CosObjectStorage/OssObjectStorage/S3ObjectStorage）。
 * P1 阶段仅留接口骨架，未来有实际存储需求时再加实现。
 */
public interface ObjectStorage {

    /** 上传对象，返回该对象的访问 key */
    String upload(String key, InputStream input, String contentType);

    /** 下载对象，返回输入流（调用方负责关闭） */
    InputStream download(String key);

    /** 生成限时签名 URL，expireSeconds 秒后过期 */
    String presignedUrl(String key, long expireSeconds);

    /** 删除对象 */
    void delete(String key);
}
