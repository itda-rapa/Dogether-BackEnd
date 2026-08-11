package itda.media.dto.uploaddto;

public record MultipartUploaded(
        int partNumber,
        String eTag
) {
}