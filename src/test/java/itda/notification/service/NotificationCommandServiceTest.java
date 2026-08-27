package itda.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.notification.domain.NotificationTargetType;
import itda.notification.domain.NotificationType;
import itda.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCommandServiceTest {

    @Mock private NotificationRepository notifications;

    @Test
    void reactionIsSkippedForSelfAction() {
        var service = new NotificationCommandService(notifications);

        assertThat(service.notifyReaction(2L, 2L, "나", null, NotificationType.BOARD_POST_LIKE,
                NotificationTargetType.BOARD_POST, 3L, 3L, null)).isFalse();

        verify(notifications, never()).insertIgnore(anyLong(), anyLong(), any(), any(), anyLong(),
                any(), any(), any(), any(), any());
    }

    @Test
    void reactionReturnsFalseWhenUniqueConflictWasIgnored() {
        var service = new NotificationCommandService(notifications);
        when(notifications.insertIgnore(2L, 1L, "SETLOG_CUTE", "SETLOG", 8L,
                null, 8L, "콩이", null, null)).thenReturn(0);

        assertThat(service.notifyReaction(2L, 1L, "콩이", null, NotificationType.SETLOG_CUTE,
                NotificationTargetType.SETLOG, 8L, null, 8L)).isFalse();
    }

    @Test
    void reactionRequiresReactionType() {
        var service = new NotificationCommandService(notifications);

        assertThatThrownBy(() -> service.notifyReaction(2L, 1L, "콩이", null,
                NotificationType.BOARD_COMMENT_CREATED, NotificationTargetType.BOARD_COMMENT, 8L, 3L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
