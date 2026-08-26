package itda.meetingreview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 후기 작성 요청(04_M3_API_상세명세.md §11 POST /meetings/{meetingId}/reviews).
 *
 * <p>{@code placeTag} 는 필수 장소 태그(공백 불가, 최대 30자)다. 기존에 허용 태그
 * enum/공용 DTO 가 없어 String 으로 저장한다. 한 줄 후기({@code content})는 선택 입력이고
 * 별점은 없다.
 *
 * @param clientRequestId 동일 Pet 재요청 식별용 전역 멱등키(UUID)
 * @param placeTag 필수 장소 태그(공백 불가, 최대 30자)
 * @param content 한 줄 후기(선택, 최대 500자)
 */
public record MeetingReviewSubmitCommand(
        @NotNull UUID clientRequestId,
        @NotBlank @Size(max = 30) String placeTag,
        @Size(max = 500) String content
) {
}
