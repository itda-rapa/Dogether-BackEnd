package itda.chat.websocket;

import itda.chat.event.ChatMessageCommittedEvent;
import itda.chat.domain.RoomType;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.SetlogMediaResponse;
import itda.chat.dto.response.SharedSetlogResponse;
import itda.chat.service.ChatRealtimeRecipientQueryService;
import itda.setlog.dto.ShareableSetlogView;
import itda.setlog.service.SetlogQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.websocket", name = "enabled", havingValue = "true")
public class ChatMessageRealtimeListener {

    private final ChatRealtimeRecipientQueryService recipientQueryService;
    private final SetlogQueryService setlogQueryService;
    private final ChatRealtimePublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCommitted(ChatMessageCommittedEvent event) {
        if (event.roomType() != RoomType.DIRECT) {
            return;
        }
        long roomId = event.message().roomId();
        List<Long> recipients;
        try {
            recipients = recipientQueryService.findActiveRecipientUserIds(
                    roomId,
                    event.message().senderPetId()
            );
        } catch (Exception exception) {
            log.warn("Realtime recipient lookup failed roomId={} messageId={} exceptionType={}",
                    roomId, event.message().messageId(), exception.getClass().getSimpleName());
            return;
        }
        for (Long userId : recipients) {
            try {
                ChatMessageCreatedWsEvent payload = new ChatMessageCreatedWsEvent(
                        ChatWebSocketEventType.CHAT_MESSAGE_CREATED,
                        event.roomType().name(),
                        responseForViewer(event.message(), userId)
                );
                publisher.publishToUser(userId, payload);
            } catch (Exception exception) {
                log.warn("Realtime publish failed userId={} roomId={} messageId={} exceptionType={}",
                        userId, roomId, event.message().messageId(), exception.getClass().getSimpleName());
            }
        }
    }

    /**
     * 실시간 SETLOG_SHARE는 전송 시점의 발신자용 요약을 그대로 fan-out하지 않는다. 수신자마다
     * 차단·삭제 상태를 다시 적용해, 조회 불가한 사용자는 URL을 포함한 preview를 절대 받지 않는다.
     */
    private ChatMessageResponse responseForViewer(ChatMessageResponse message, long viewerUserId) {
        SharedSetlogResponse sharedSetlog = message.sharedSetlog();
        if (sharedSetlog == null || sharedSetlog.setlogId() == null) {
            return message;
        }

        ShareableSetlogView view;
        try {
            view = setlogQueryService
                    .findShareableSetlogViews(List.of(sharedSetlog.setlogId()), viewerUserId)
                    .getOrDefault(sharedSetlog.setlogId(), ShareableSetlogView.unavailable(sharedSetlog.setlogId()));
        } catch (Exception exception) {
            // 접근 정책을 재확인할 수 없으면 기존 preview를 재사용하지 않고 fail closed 한다.
            log.warn("Realtime setlog access lookup failed userId={} messageId={} exceptionType={}",
                    viewerUserId, message.messageId(), exception.getClass().getSimpleName());
            view = ShareableSetlogView.unavailable(sharedSetlog.setlogId());
        }

        return new ChatMessageResponse(
                message.messageId(), message.roomId(), message.senderType(), message.senderPetId(),
                message.senderPetNickname(), message.type(), message.body(), message.attachment(),
                toSharedSetlogResponse(view), message.meetingCardId(), message.clientMessageId(), message.createdAt());
    }

    private SharedSetlogResponse toSharedSetlogResponse(ShareableSetlogView view) {
        if (!view.available()) {
            return SharedSetlogResponse.unavailable(view.setlogId());
        }
        SetlogMediaResponse media = view.mediaId() == null ? null : new SetlogMediaResponse(
                view.mediaId(), view.mediaType(), view.mediaUrl(), view.mediaUrlExpiresAt());
        return new SharedSetlogResponse(
                view.setlogId(), true, null, view.authorPetId(), view.authorPetNickname(), view.caption(), media,
                view.reactionCount(), "/setlogs/" + view.setlogId());
    }
}
