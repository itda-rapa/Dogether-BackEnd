package itda.chat.service;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomStatus;
import itda.chat.domain.RoomType;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.friend.repository.FriendshipRepository;
import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.repository.GreetingRepository;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.repository.CardDraftRepository;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.report.repository.ReportRepository;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceEventCommand;
import itda.risk.contract.RiskSourceEventPublisher;
import itda.risk.contract.RiskSourceType;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomLifecycleTransactionService {

    private final GreetingRepository greetingRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final CardDraftRepository cardDraftRepository;
    private final MeetingCardRepository meetingCardRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final ReportRepository reportRepository;
    private final FriendshipRepository friendshipRepository;
    private final InteractionPairLockService interactionPairLockService;
    private final RiskSourceEventPublisher riskSourceEventPublisher;

    @Transactional
    public ExpiredGreetingOutcome expireGreetingAndCleanup(
            long greetingId,
            long candidateRoomId,
            Long candidatePetLowId,
            Long candidatePetHighId,
            Instant now
    ) {
        // Friend acceptance and archive maintenance both lock the interaction pair before
        // touching the room. Keep the same order here to prevent pair/room deadlocks.
        if (candidatePetLowId != null && candidatePetHighId != null) {
            interactionPairLockService.lockInteractionPair(
                    candidatePetLowId,
                    candidatePetHighId
            );
        }
        ChatRoom room = chatRoomRepository.findByIdForUpdate(candidateRoomId)
                .orElse(null);
        Greeting greeting = greetingRepository.findByIdForUpdate(greetingId)
                .orElse(null);
        if (greeting == null
                || greeting.getStatus() != GreetingStatus.SENT
                || greeting.getExpiresAt().isAfter(now)
                || greeting.getRoomId() != candidateRoomId) {
            return new ExpiredGreetingOutcome(false, false);
        }

        expireAndPublishRiskEvent(greeting);

        if (room == null) {
            return new ExpiredGreetingOutcome(true, false);
        }

        boolean reported = reportRepository.existsByRoomId(room.getId());
        boolean answered = greetingRepository.existsByRoomIdAndStatus(
                room.getId(), GreetingStatus.RESPONDED);
        var sentGreetings = greetingRepository.findSentByRoomIdForUpdate(room.getId());
        boolean anotherGreetingStillPending = sentGreetings.stream()
                .anyMatch(candidate -> candidate.getExpiresAt().isAfter(now));

        boolean friendshipExists = room.getPetLowId() != null
                && room.getPetHighId() != null
                && friendshipRepository.existsByPetLowIdAndPetHighId(
                room.getPetLowId(),
                room.getPetHighId()
        );

        if (reported || answered || anotherGreetingStillPending || friendshipExists) {
            return new ExpiredGreetingOutcome(true, false);
        }

        sentGreetings.stream()
                .filter(candidate -> candidate != greeting)
                .forEach(this::expireAndPublishRiskEvent);
        chatMessageRepository.deleteByRoomId(room.getId());
        meetingParticipantRepository.deleteByRoomId(room.getId());
        meetingCardRepository.deleteByRoomId(room.getId());
        cardDraftRepository.deleteByRoomId(room.getId());
        participantRepository.deleteByRoomId(room.getId());
        chatRoomRepository.delete(room);
        chatRoomRepository.flush();
        return new ExpiredGreetingOutcome(true, true);
    }

    private void expireAndPublishRiskEvent(Greeting greeting) {
        if (greeting.getStatus() != GreetingStatus.SENT) {
            return;
        }

        greeting.expire();
        riskSourceEventPublisher.enqueue(new RiskSourceEventCommand(
                RiskSourceType.GREETING,
                greeting.getId(),
                RiskSignalType.GREETING_EXPIRED,
                greeting.getFromPet().getOwner().getId(),
                greeting.getToPet().getOwner().getId(),
                greeting.getExpiresAt(),
                java.util.Map.of()
        ));
    }

    @Transactional
    public boolean archiveIfEligible(
            long roomId,
            long petLowId,
            long petHighId,
            Instant now,
            Instant cutoff
    ) {
        interactionPairLockService.lockInteractionPair(petLowId, petHighId);
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElse(null);
        if (room == null
                || room.getType() != RoomType.DIRECT
                || room.getStatus() != RoomStatus.ACTIVE
                || room.getLastMessageAt() == null
                || room.getLastMessageAt().isAfter(cutoff)
                || !greetingRepository.existsByRoomIdAndStatus(
                        roomId, GreetingStatus.RESPONDED)) {
            return false;
        }

        if (friendshipRepository.existsByPetLowIdAndPetHighId(
                room.getPetLowId(), room.getPetHighId())) {
            return false;
        }

        room.archive(now);
        return true;
    }

    public record ExpiredGreetingOutcome(
            boolean expired,
            boolean roomDeleted
    ) {
    }
}
