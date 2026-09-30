package com.qatools.service;

import com.qatools.config.MinioProperties;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
public class MinioStorageService {

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioProperties minioProperties;

    private static final int PRESIGNED_URL_EXPIRY_DAYS = 7;

    /** 确保 bucket 存在 */
    public void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(minioProperties.getBucket()).build()
        );
        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(minioProperties.getBucket()).build()
            );
            // 设置 bucket 公共读（图片需要直接访问）
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + minioProperties.getBucket() + "/*\"]}]}";
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder().bucket(minioProperties.getBucket()).config(policy).build()
            );
        }
    }

    /** 上传文件流，返回对象 key */
    public String upload(String objectName, InputStream stream, long size, String contentType) throws Exception {
        ensureBucket();
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(objectName)
                        .stream(stream, size, -1)
                        .contentType(contentType != null ? contentType : "application/octet-stream")
                        .build()
        );
        return objectName;
    }

    /** 上传字节数组 */
    public String upload(String objectName, byte[] data, String contentType) throws Exception {
        return upload(objectName, new ByteArrayInputStream(data), data.length, contentType);
    }

    /** 获取对象直链 URL（bucket 已设公共读） */
    public String getUrl(String objectName) {
        return minioProperties.getEndpoint() + "/" + minioProperties.getBucket() + "/" + objectName;
    }

    /** getUrl 的逆操作：从直链 URL 反推对象 key；非 MinIO 的直链 URL 返回 null */
    public String extractObjectName(String url) {
        if (url == null) return null;
        String prefix = minioProperties.getEndpoint() + "/" + minioProperties.getBucket() + "/";
        return url.startsWith(prefix) ? url.substring(prefix.length()) : null;
    }



    /** 下载对象内容 */
    public byte[] download(String objectName) throws Exception {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(minioProperties.getBucket()).object(objectName).build())) {
            return stream.readAllBytes();
        }
    }

    /** 获取预签名 URL（私有 bucket 用） */
    public String getPresignedUrl(String objectName) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(minioProperties.getBucket())
                        .object(objectName)
                        .expiry(PRESIGNED_URL_EXPIRY_DAYS, TimeUnit.DAYS)
                        .build()
        );
    }
}
