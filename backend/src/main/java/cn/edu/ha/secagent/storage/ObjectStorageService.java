package cn.edu.ha.secagent.storage;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ObjectStorageService {
    private static final Set<String> AVATAR_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final long MAX_ATTACHMENT_SIZE = 15 * 1024 * 1024L;
    private static final Map<String, String> ATTACHMENT_TYPES = Map.ofEntries(
            Map.entry(".png", "image/png"), Map.entry(".jpg", "image/jpeg"), Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".webp", "image/webp"), Map.entry(".gif", "image/gif"), Map.entry(".pdf", "application/pdf"),
            Map.entry(".txt", "text/plain"), Map.entry(".csv", "text/csv"), Map.entry(".json", "application/json"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".ppt", "application/vnd.ms-powerpoint"),
            Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    );
    private final io.minio.MinioClient client;
    private final AppProperties properties;

    public String saveAvatar(UUID userId, MultipartFile file) {
        var detectedType = detectImageType(file);
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024L || detectedType == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR", "头像必须是5MB以内的PNG、JPG或WebP图片");
        }
        var extension = switch (detectedType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        var objectKey = "avatars/" + userId + "/" + UUID.randomUUID() + extension;
        try (InputStream stream = file.getInputStream()) {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(objectKey)
                    .stream(stream, file.getSize(), -1)
                    .contentType(detectedType)
                    .build());
            return objectKey;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "文件存储服务暂不可用");
        }
    }

    public SavedAttachment saveAttachment(UUID userId, MultipartFile file) {
        var originalName = safeName(file.getOriginalFilename());
        var extension = extensionOf(originalName);
        var expectedType = ATTACHMENT_TYPES.get(extension);
        if (file.isEmpty() || file.getSize() > MAX_ATTACHMENT_SIZE || expectedType == null || !signatureMatches(file, extension)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
                    "仅支持15MB以内的图片、PDF、Word、Excel、PPT、TXT、CSV或JSON文件");
        }
        var contentType = expectedType;
        var objectKey = "attachments/" + userId + "/" + UUID.randomUUID() + extension;
        try (InputStream stream = file.getInputStream()) {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(objectKey)
                    .stream(stream, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
            return new SavedAttachment(objectKey, originalName, contentType, file.getSize());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "文件存储服务暂不可用");
        }
    }

    private static String safeName(String value) {
        var name = value == null ? "attachment" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank()) name = "attachment";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String extensionOf(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        var index = lower.lastIndexOf('.');
        return index < 0 ? "" : lower.substring(index);
    }

    private static boolean signatureMatches(MultipartFile file, String extension) {
        try (var stream = file.getInputStream()) {
            var header = stream.readNBytes(12);
            if (Set.of(".png", ".jpg", ".jpeg", ".webp").contains(extension)) return detectImageType(file) != null;
            if (".gif".equals(extension)) return header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F';
            if (".pdf".equals(extension)) return header.length >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F' && header[4] == '-';
            if (Set.of(".docx", ".xlsx", ".pptx").contains(extension)) {
                return header.length >= 4 && header[0] == 'P' && header[1] == 'K';
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String detectImageType(MultipartFile file) {
        if (file.isEmpty()) return null;
        try (var stream = file.getInputStream()) {
            byte[] header = stream.readNBytes(12);
            if (header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47) {
                return "image/png";
            }
            if (header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff) {
                return "image/jpeg";
            }
            if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
                return "image/webp";
            }
        } catch (Exception ignored) {
            return null;
        }
        return AVATAR_TYPES.contains(file.getContentType()) ? file.getContentType() : null;
    }

    public StoredObject read(String objectKey) {
        try {
            var stat = client.statObject(StatObjectArgs.builder()
                    .bucket(properties.minio().bucket()).object(objectKey).build());
            var stream = client.getObject(GetObjectArgs.builder()
                    .bucket(properties.minio().bucket()).object(objectKey).build());
            return new StoredObject(stream.readAllBytes(), stat.contentType());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在");
        }
    }

    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.minio().bucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "文件存储服务暂不可用");
        }
    }

    private void ensureBucket() throws Exception {
        var bucket = properties.minio().bucket();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public record StoredObject(byte[] bytes, String contentType) {}
    public record SavedAttachment(String objectKey, String originalName, String contentType, long sizeBytes) {}
}
