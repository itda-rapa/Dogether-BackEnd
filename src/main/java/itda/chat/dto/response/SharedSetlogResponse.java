package itda.chat.dto.response;

import java.time.Instant;

/**
 * SETLOG_SHARE 메시지의 공유 Setlog 표현. 조회 시점의 현재 접근 가능 요약이며, 접근 불가 시
 * {@code available=false}와 null 필드로 대체한다(원문 preview를 노출하지 않는다).
 */
public record SharedSetlogResponse(
        Long setlogId,
        boolean available,
        String unavailableReason,
        Long authorPetId,
        String authorPetNickname,
        String caption,
        SetlogMediaResponse media,
        Integer reactionCount,
        String detailPath
) {

    public static SharedSetlogResponse unavailable(Long setlogId) {
        return new SharedSetlogResponse(
                setlogId,
                false,
                "SETLOG_UNAVAILABLE",
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
