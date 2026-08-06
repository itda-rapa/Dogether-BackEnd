package itda.meetingcard.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.exception.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MeetingCardCursorCodecTest {

    @Test
    void roundTripPreservesMeetAtAndCardId() {
        Instant meetAt = Instant.parse("2026-08-06T12:34:56Z");

        String encoded = MeetingCardCursorCodec.encode(42L, meetAt);

        assertThat(MeetingCardCursorCodec.decode(encoded))
                .isEqualTo(new MeetingCardCursorCodec.CursorPayload(meetAt, 42L));
    }

    @Test
    void malformedCursorIsValidationFailure() {
        assertThatThrownBy(() -> MeetingCardCursorCodec.decode("not-a-cursor"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blankCursorMeansFirstPage() {
        assertThat(MeetingCardCursorCodec.decode(null)).isNull();
        assertThat(MeetingCardCursorCodec.decode(" ")).isNull();
    }
}
