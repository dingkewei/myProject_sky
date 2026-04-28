package com.sky.controller.common;

import com.sky.properties.AliOssProperties;
import com.sky.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/common")
@Slf4j
public class CommonController {

    @Autowired
    private AliOssUtil aliOssUtil;

    @Autowired
    private AliOssProperties aliOssProperties;

    @GetMapping("/download")
    public void download(String name, HttpServletResponse response) throws IOException {
        String decodedName = decodeName(name);
        String fileNameSource = decodedName;
        byte[] bytes;

        if (isRemoteUrl(decodedName) && !isCurrentBucketUrl(decodedName)) {
            log.info("Proxy remote image: {}", decodedName);
            bytes = downloadRemote(decodedName);
        } else {
            String objectName = resolveObjectName(decodedName);
            fileNameSource = objectName;
            log.info("Download OSS image: {}", objectName);
            bytes = aliOssUtil.download(objectName);
        }

        response.setContentType(resolveContentType(fileNameSource));
        response.setContentLength(bytes.length);
        response.setHeader("Cache-Control", "public, max-age=86400");
        response.setHeader("Content-Disposition", "inline; filename=\"" + getFileName(fileNameSource) + "\"");
        response.getOutputStream().write(bytes);
    }

    private String decodeName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Image name cannot be empty");
        }
        return URLDecoder.decode(name, StandardCharsets.UTF_8.name());
    }

    private String resolveObjectName(String decodedName) {
        if (isRemoteUrl(decodedName)) {
            String path = URI.create(decodedName).getPath();
            return path.startsWith("/") ? path.substring(1) : path;
        }
        return decodedName.startsWith("/") ? decodedName.substring(1) : decodedName;
    }

    private boolean isRemoteUrl(String name) {
        return name.startsWith("http://") || name.startsWith("https://");
    }

    private boolean isCurrentBucketUrl(String name) {
        String host = URI.create(name).getHost();
        String currentHost = aliOssProperties.getBucketName() + "." + aliOssProperties.getEndpoint();
        return currentHost.equalsIgnoreCase(host);
    }

    private byte[] downloadRemote(String imageUrl) throws IOException {
        try (InputStream inputStream = new URL(imageUrl).openStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            return outputStream.toByteArray();
        }
    }

    private String resolveContentType(String objectName) {
        String lowerName = objectName.toLowerCase();
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (lowerName.endsWith(".gif")) {
            return MediaType.IMAGE_GIF_VALUE;
        }
        return MediaType.IMAGE_PNG_VALUE;
    }

    private String getFileName(String objectName) {
        int index = objectName.lastIndexOf('/');
        return index >= 0 ? objectName.substring(index + 1) : objectName;
    }
}
