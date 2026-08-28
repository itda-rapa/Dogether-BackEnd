package itda.meetingcard.service;

import itda.chat.domain.ChatRoomParticipant;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.CardDraftParticipant;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.event.OpenChatDraftAiResultEvent;
import itda.meetingcard.dto.event.OpenChatDraftReadyEvent;
import itda.meetingcard.repository.CardDraftParticipantRepository;
import itda.meetingcard.repository.CardDraftRepository;
import itda.pet.repository.PetRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OpenChatDraftResultService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_PLACE_TEXT = 500;

    private final CardDraftRepository cardDraftRepository;
    private final CardDraftParticipantRepository participantRepository;
    private final ChatRoomParticipantRepository roomParticipantRepository;
    private final PetRepository petRepository;

    @Transactional
    public Map<Long, List<OpenChatDraftReadyEvent.Draft>> persist(
            OpenChatDraftAiResultEvent event
    ) {
        List<CardDraft> alreadySaved = cardDraftRepository
                .findByRequestIdOrderByCandidateIndexAsc(event.requestId());
        List<SavedDraft> savedDrafts = alreadySaved.isEmpty()
                ? saveNew(event)
                : loadExisting(alreadySaved);

        List<Long> selectedPetIds = savedDrafts.stream()
                .flatMap(saved -> saved.participantPetIds().stream())
                .distinct()
                .toList();
        Map<Long, Long> ownerByPetId = (selectedPetIds.isEmpty()
                ? List.<PetRepository.PetOwnerRow>of()
                : petRepository.findOwnerRows(selectedPetIds))
                .stream()
                .collect(Collectors.toMap(
                        PetRepository.PetOwnerRow::getPetId,
                        PetRepository.PetOwnerRow::getOwnerUserId));

        Map<Long, List<OpenChatDraftReadyEvent.Draft>> draftsByUser = new LinkedHashMap<>();
        for (SavedDraft saved : savedDrafts) {
            OpenChatDraftReadyEvent.Draft notificationDraft = toNotification(saved);
            saved.participantPetIds().stream()
                    .map(ownerByPetId::get)
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(userId -> draftsByUser
                            .computeIfAbsent(userId, ignored -> new ArrayList<>())
                            .add(notificationDraft));
        }
        draftsByUser.putIfAbsent(event.requesterUserId(), new ArrayList<>());
        return draftsByUser;
    }

    private List<SavedDraft> saveNew(OpenChatDraftAiResultEvent event) {
        Set<Long> activeRoomPets = roomParticipantRepository.findByRoomId(event.roomId()).stream()
                .filter(participant -> participant.getLeftAt() == null)
                .map(ChatRoomParticipant::getPetId)
                .collect(Collectors.toSet());
        List<SavedDraft> saved = new ArrayList<>();
        for (OpenChatDraftAiResultEvent.Draft candidate : safeDrafts(event.drafts())) {
            List<Long> participantPetIds = normalizeParticipants(
                    candidate.participantPetIds(), activeRoomPets);
            if (candidate.candidateIndex() == null || participantPetIds.size() < 2) {
                continue;
            }
            CardDraft draft = cardDraftRepository.saveAndFlush(new CardDraft(
                    event.roomId(),
                    event.requesterPetId(),
                    meetingType(candidate.meetingType()),
                    bounded(candidate.place()),
                    meetAt(candidate.date(), candidate.time()),
                    normalizedDate(candidate.date()),
                    normalizedTime(candidate.time()),
                    null,
                    event.requestId(),
                    candidate.candidateIndex()
            ));
            participantPetIds.stream()
                    .map(petId -> new CardDraftParticipant(draft.getId(), petId))
                    .forEach(participantRepository::save);
            saved.add(new SavedDraft(draft, participantPetIds));
        }
        return saved;
    }

    private List<SavedDraft> loadExisting(List<CardDraft> drafts) {
        return drafts.stream()
                .map(draft -> new SavedDraft(
                        draft,
                        participantRepository.findByCardDraftIdOrderByIdAsc(draft.getId()).stream()
                                .map(CardDraftParticipant::getPetId)
                                .toList()))
                .toList();
    }

    private List<Long> normalizeParticipants(List<Long> raw, Set<Long> activeRoomPets) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(Objects::nonNull)
                .filter(activeRoomPets::contains)
                .distinct()
                .toList();
    }

    private List<OpenChatDraftAiResultEvent.Draft> safeDrafts(
            List<OpenChatDraftAiResultEvent.Draft> drafts
    ) {
        return drafts == null ? List.of() : drafts.stream().filter(Objects::nonNull).toList();
    }

    private OpenChatDraftReadyEvent.Draft toNotification(SavedDraft saved) {
        CardDraft draft = saved.draft();
        return new OpenChatDraftReadyEvent.Draft(
                draft.getId(), draft.getRoomId(), draft.getRequestedByPetId(),
                draft.getCardType(), draft.getPlaceText(), draft.getDate(), draft.getTime(),
                draft.getMeetAt(), draft.isFallback(),
                draft.getFallbackReason() == null ? null : draft.getFallbackReason().name(),
                draft.getCreatedAt(), saved.participantPetIds());
    }

    private MeetingCardType meetingType(String raw) {
        try {
            return raw == null ? null : MeetingCardType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String bounded(String value) {
        return value == null || value.length() <= MAX_PLACE_TEXT
                ? value : value.substring(0, MAX_PLACE_TEXT);
    }

    private String normalizedDate(String value) {
        try {
            return value == null ? null : LocalDate.parse(value).toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizedTime(String value) {
        try {
            return value == null ? null : LocalTime.parse(value)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Instant meetAt(String date, String time) {
        String normalizedDate = normalizedDate(date);
        String normalizedTime = normalizedTime(time);
        if (normalizedDate == null || normalizedTime == null) return null;
        return LocalDate.parse(normalizedDate).atTime(LocalTime.parse(normalizedTime))
                .atZone(SEOUL).toInstant();
    }

    private record SavedDraft(CardDraft draft, List<Long> participantPetIds) {
    }
}
