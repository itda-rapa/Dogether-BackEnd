package itda.setlog.dto;

import java.time.Instant;

/**
 * Chat SETLOG_SHARE hydration용 setlog 요약. 조회 시점의 현재 상태를 담으며, 접근 불가 시
 * {@code available=false}로 표현한다. Chat은 이 계약만 사용하고 setlog 내부를 직접 해석하지 않는다.
 */
public record ShareableSetlogView(
        Long setlogId,
        boolean available,
        Long authorPetId,
        String authorPetNickname,
        String caption,
        Long mediaId,
        String mediaType,
        String mediaUrl,
        Instant mediaUrlExpiresAt,
        int reactionCount
) {

    public static ShareableSetlogView unavailable(Long setlogId) {
        return new ShareableSetlogView(setlogId, false, null, null, null, null, null, null, null, 0);
    }
}
