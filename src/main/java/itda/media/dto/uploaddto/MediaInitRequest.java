package itda.media.dto.uploaddto;


import itda.media.domain.MediaType;

public record MediaInitRequest(
        MediaType mediaType,
        String contentType,
        Long fileSize
) {
}
