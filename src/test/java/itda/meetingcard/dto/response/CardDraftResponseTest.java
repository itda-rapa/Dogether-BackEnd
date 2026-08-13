package itda.meetingcard.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.MeetingCardType;
import org.junit.jupiter.api.Test;

class CardDraftResponseTest {

    @Test
    void preservesTimeWhenDateAndMeetAtAreMissing() {
        CardDraft draft = new CardDraft(
                1L, 2L, MeetingCardType.PLAY, "모란시장역",
                null, null, "09:00", null);

        CardDraftResponse response = CardDraftResponse.from(draft);

        assertThat(response.date()).isNull();
        assertThat(response.time()).isEqualTo("09:00");
        assertThat(response.meetAt()).isNull();
        assertThat(response.placeText()).isEqualTo("모란시장역");
    }
}
