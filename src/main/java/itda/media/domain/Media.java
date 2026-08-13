package itda.media.domain;


import itda.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "media")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Media extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    //
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaType mediaType; // 저장할 수 있는 미디어파일 타입
    //
    @Column(nullable = false, length = 2000)
    private String path; // RustFS 상 미디어파일 저장 경로
    //
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaStatus status; // 미디어파일 상태
    //
    @Column
    private Long userId; // 업로드 한 유저ID
    //
    @Column(length = 500)
    private String uploadId; // 멀티파트 업로드
    //
    @Column
    private Long fileSize; // 업로드 파일 크기

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(length = 512)
    private String etag;

    @Column(name = "object_version_id", length = 1024)
    private String objectVersionId;

    @Column(name = "storage_last_modified")
    private Instant storageLastModified;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column
    private Instant deletedAt;
    //
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attributes; // JSON
    // 단일 업로드 시 생성하는 Media 객체
    public Media(
            MediaType mediaType,
            String path,
            Long userId,
            Long fileSize
    ){
        this.mediaType = mediaType;
        this.path = path;
        this.status = MediaStatus.INIT;
        this.userId = userId;
        this.fileSize = fileSize;
    }
    // 멀티파트 업로드 시 생성하는 Media 객체
    public Media(
            MediaType mediaType,
            String path,
            Long userId,
            Long fileSize,
            String uploadId
    ){
        this.mediaType = mediaType;
        this.path = path;
        this.status = MediaStatus.INIT;
        this.userId = userId;
        this.fileSize = fileSize;
        this.uploadId = uploadId;
    }
    public void updateStatus(MediaStatus status) {
        this.status = status;
    }
    public void updateAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public static Media verifiedVideo(
            String path,
            Long userId,
            long fileSize,
            String contentType,
            String etag,
            String objectVersionId,
            Instant storageLastModified,
            Instant verifiedAt
    ) {
        Media media = new Media(MediaType.VIDEO, path, userId, fileSize);
        media.status = MediaStatus.COMPLETED;
        media.contentType = contentType;
        media.etag = etag;
        media.objectVersionId = objectVersionId;
        media.storageLastModified = storageLastModified;
        media.verifiedAt = verifiedAt;
        return media;
    }

    public void markDeleted(Instant deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = java.util.Objects.requireNonNull(deletedAt);
        }
    }
}
