package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.dto.response.SharedSetlogResponse;
import itda.setlog.dto.ShareableSetlogView;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SharedSetlogResponseMapperTest {

    private final SharedSetlogResponseMapper mapper = new SharedSetlogResponseMapper();

    @Test
    void mapsAvailableViewToSharedSetlogCard() {
        ShareableSetlogView view = new ShareableSetlogView(
                77L, true, 11L, "몽", "산책", 501L, "IMAGE",
                "https://storage.example/setlog", Instant.parse("2026-08-26T04:00:00Z"), 3);
        SharedSetlogResponse response = mapper.toResponse(view);

        assertThat(response)
                .extracting("setlogId", "available", "authorPetId", "authorPetNickname", "caption",
                        "reactionCount", "detailPath")
                .containsExactly(77L, true, 11L, "몽", "산책", 3, "/setlogs/77");
        assertThat(response.media())
                .extracting("mediaId", "mediaType", "url", "expiresAt")
                .containsExactly(501L, "IMAGE", "https://storage.example/setlog",
                        Instant.parse("2026-08-26T04:00:00Z"));
    }

    @Test
    void mapsUnavailableViewWithoutPreviewFields() {
        assertThat(mapper.toResponse(ShareableSetlogView.unavailable(77L)))
                .extracting("setlogId", "available", "unavailableReason", "authorPetId", "authorPetNickname",
                        "caption", "media", "reactionCount", "detailPath")
                .containsExactly(77L, false, "SETLOG_UNAVAILABLE", null, null, null, null, null, null);
    }
}
