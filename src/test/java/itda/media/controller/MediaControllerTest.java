package itda.media.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import itda.common.security.CurrentUser;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.service.MediaService;
import itda.user.domain.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MediaControllerTest {

    @Test
    void presignedDownloadResponseIsNoStore() {
        MediaService mediaService = mock(MediaService.class);
        Media media = mock(Media.class);
        given(media.getId()).willReturn(7L);
        given(media.getMediaType()).willReturn(MediaType.VIDEO);
        given(media.getPath()).willReturn("setlogs/1/12/video.mp4");
        given(media.getStatus()).willReturn(MediaStatus.COMPLETED);
        given(media.getUserId()).willReturn(1L);
        given(media.getFileSize()).willReturn(1024L);
        given(mediaService.getOwnedPresignedDownload(7L, 1L)).willReturn(
                new MediaService.OwnedPresignedDownload(
                        media,
                        new MediaService.PresignedDownloadUrl(
                                "https://storage.example/video",
                                Instant.parse("2026-08-12T01:10:00Z")
                        )
                )
        );
        MediaController controller = new MediaController(mediaService);

        var response = controller.getPresignedUrl(
                new CurrentUser(1L, "owner@example.com", Role.USER), 7L
        );

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody().data().presignedUrl())
                .isEqualTo("https://storage.example/video");
    }
}
