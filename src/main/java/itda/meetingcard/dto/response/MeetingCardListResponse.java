package itda.meetingcard.dto.response;

import itda.chat.dto.response.CursorPage;
import java.util.List;

public record MeetingCardListResponse(
        List<MeetingCardResponse> items,
        CursorPage page
) {

    public MeetingCardListResponse {
        items = List.copyOf(items);
    }
}
