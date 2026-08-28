package itda.meetingcard.dto;

import itda.meetingcard.domain.MeetingCardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 지도 메시지의 시설을 약속 장소로 사용하기 위한 수동 초안 요청. */
public record OpenChatPlaceDraftRequest(
        @NotBlank @Size(max = 500) String placeText,
        @NotNull MeetingCardType cardType
) {
}
