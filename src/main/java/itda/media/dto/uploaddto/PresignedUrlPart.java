package itda.media.dto.uploaddto;

public record PresignedUrlPart(
        int partNumber,
        String presignedUrl
) {
}