package itda.meetingcard.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenChatDraftRequestServiceTest {

    private final OpenChatDraftRequestService service = new OpenChatDraftRequestService(
            null, null, null, null, null, null, Clock.systemUTC());

    @Test
    void participantSnapshotKeepsOnlyRoomMembersAndAlwaysIncludesRequesterFirst() {
        assertThat(service.normalizeParticipants(
                List.of("22", "999", "22", "bad"), List.of(11L, 22L, 33L), 11L))
                .containsExactly(11L, 22L);
    }

    @Test
    void missingAiParticipantsStillKeepsRequesterButDoesNotSatisfyQuorum() {
        assertThat(service.normalizeParticipants(null, List.of(11L, 22L, 33L), 11L))
                .containsExactly(11L);
    }

    @Test
    void requesterOutsideRoomProducesNoSnapshot() {
        assertThat(service.normalizeParticipants(List.of("22"), List.of(22L, 33L), 11L))
                .isEmpty();
    }

    @Test
    void sentAtUsesIsoOffsetDateTimeWithoutJavaZoneRegionSuffix() {
        assertThat(OpenChatDraftRequestService.formatSentAt(
                Instant.parse("2026-08-13T00:00:00Z")))
                .isEqualTo("2026-08-13T09:00:00+09:00")
                .doesNotContain("[Asia/Seoul]");
    }
}
