package cn.edu.ha.secagent.storage;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ObjectStorageService {
    private static final Set<String> AVATAR_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
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

    private void ensureBucket() throws Exception {
        var bucket = properties.minio().bucket();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public record StoredObject(byte[] bytes, String contentType) {}
}
