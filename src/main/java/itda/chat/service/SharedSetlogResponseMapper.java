package itda.chat.service;

import itda.chat.dto.response.SetlogMediaResponse;
import itda.chat.dto.response.SharedSetlogResponse;
import itda.setlog.dto.ShareableSetlogView;
import org.springframework.stereotype.Component;

/**
 * 조회 권한이 반영된 Setlog 요약 뷰를 채팅 공유 카드 응답으로 변환한다.
 */
@Component
public class SharedSetlogResponseMapper {

    public SharedSetlogResponse toResponse(ShareableSetlogView view) {
        if (!view.available()) {
            return SharedSetlogResponse.unavailable(view.setlogId());
        }
        SetlogMediaResponse media = view.mediaId() == null
                ? null
                : new SetlogMediaResponse(
                        view.mediaId(),
                        view.mediaType(),
                        view.mediaUrl(),
                        view.mediaUrlExpiresAt()
                );
        return new SharedSetlogResponse(
                view.setlogId(),
                true,
                null,
                view.authorPetId(),
                view.authorPetNickname(),
                view.caption(),
                media,
                view.reactionCount(),
                "/setlogs/" + view.setlogId()
        );
    }
}
