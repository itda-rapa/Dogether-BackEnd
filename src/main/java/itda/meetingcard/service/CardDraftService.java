package itda.meetingcard.service;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.MessageType;
import itda.chat.domain.SenderType;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.service.ChatQueryService;
import itda.meetingcard.ai.AiDraftCommand;
import itda.meetingcard.ai.AiDraftResult;
import itda.meetingcard.ai.MeetingDraftAiClient;
import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.dto.response.CardDraftResponse;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 약속 초안 생성.
 *
 * <p>AI 쪽 결과는 무엇이든 200 으로 수렴한다. 실패·지연·컨텍스트 부족을 사용자에게 5xx 로
 * 흘리지 않고 빈 폼을 돌려주는 것이 계약이다. 반면 인증·Active Pet·방 접근은 그 앞단이라
 * 여전히 401·403·404 를 낸다.
 */
@Service
public class CardDraftService {

    /** AI 에 넘기는 원본 메시지 상한. */
    private static final int MAX_SOURCE_MESSAGES = 30;
    /** 원본 메시지 수집 구간. */
    private static final Duration SOURCE_WINDOW = Duration.ofHours(24);
    /**
     * 이 개수 이하면 AI 를 호출하지 않는다. 대화가 한 줄뿐이면 약속을 뽑을 수 없고,
     * 부르면 5초를 그냥 태운다.
     */
    private static final int MIN_SOURCE_MESSAGES = 2;

    /** AI 가 존 없는 date/time 을 주므로 조합 기준 존을 서버가 확정한다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * {@code card_drafts.place_text} 의 컬럼 폭. AI 가 장소를 못 뽑고 대화 문장을 그대로
     * 넣어 보내는 경우가 있어 저장 전에 자른다.
     *
     * <p>엔티티의 {@code @Column(length = 500)} 은 스키마 메타데이터일 뿐 저장 시 검증하지
     * 않는다. 그대로 넘기면 DataIntegrityViolationException 이 나고 GlobalExceptionHandler
     * 가 500 을 낸다. "AI 결과는 무엇이든 200" 계약이 여기서 깨진다.
     */
    private static final int MAX_PLACE_TEXT = 500;

    private final ActivePetQueryService activePetQueryService;
    private final ChatQueryService chatQueryService;
    private final ChatMessageRepository chatMessageRepository;
    private final CardDraftTransactionService cardDraftTransactionService;
    private final MeetingDraftAiClient aiClient;
    private final Clock clock;

    // 생성자가 둘이므로 주입 대상을 명시한다. 없으면 Spring 이 기본 생성자를 찾다가 실패한다.
    @Autowired
    public CardDraftService(ActivePetQueryService activePetQueryService,
                            ChatQueryService chatQueryService,
                            ChatMessageRepository chatMessageRepository,
                            CardDraftTransactionService cardDraftTransactionService,
                            MeetingDraftAiClient aiClient) {
        this(activePetQueryService, chatQueryService, chatMessageRepository,
                cardDraftTransactionService, aiClient, Clock.systemUTC());
    }

    CardDraftService(ActivePetQueryService activePetQueryService,
                     ChatQueryService chatQueryService,
                     ChatMessageRepository chatMessageRepository,
                     CardDraftTransactionService cardDraftTransactionService,
                     MeetingDraftAiClient aiClient,
                     Clock clock) {
        this.activePetQueryService = activePetQueryService;
        this.chatQueryService = chatQueryService;
        this.chatMessageRepository = chatMessageRepository;
        this.cardDraftTransactionService = cardDraftTransactionService;
        this.aiClient = aiClient;
        this.clock = clock;
    }

    /**
     * 이 메서드는 트랜잭션을 열지 않는다. 안에 최대 5초짜리 AI HTTP 호출이 있어서,
     * 트랜잭션을 걸면 그 시간 동안 DB 커넥션이 놀면서 점유된다. 검사들은 각자 자기
     * 트랜잭션을 갖고 있고 쓰기만 {@link CardDraftTransactionService} 에 위임한다.
     *
     * <p>{@code NEVER} 를 다른 값으로 바꾸거나 지우면 이 보호가 사라진다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public CardDraftResponse createDraft(Long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // 방 없음·참가자 아님·차단은 모두 404 로 수렴한다. 채팅과 같은 검사를 재사용해
        // 카드 쪽에서 은닉 기준이 갈리지 않게 한다.
        chatQueryService.requireParticipant(roomId, actor.petId());
        chatQueryService.requireGreetingReplyCompleted(roomId);

        List<ChatMessage> newestFirst = loadSourceMessages(roomId);

        AiDraftResult result = newestFirst.size() < MIN_SOURCE_MESSAGES
                ? AiDraftResult.fallback(CardDraftFallbackReason.INSUFFICIENT_CONTEXT)
                : aiClient.extract(toCommand(roomId, newestFirst));

        CardDraft saved = cardDraftTransactionService.save(new CardDraft(
                roomId,
                actor.petId(),
                result.cardType(),
                truncatePlace(result.place()),
                result.combinedInstant(),
                normalizeDate(result.date()),
                normalizeTime(result.time()),
                result.fallbackReason()
        ));

        return CardDraftResponse.from(saved);
    }

    /**
     * 초안은 사용자가 확인하고 고치는 폼이므로, 500 에러 대신 잘라서 보여주는 쪽이 낫다.
     */
    private String truncatePlace(String place) {
        if (place == null || place.length() <= MAX_PLACE_TEXT) {
            return place;
        }
        return place.substring(0, MAX_PLACE_TEXT);
    }

    private String normalizeDate(String date) {
        try {
            return date == null ? null : LocalDate.parse(date).toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeTime(String time) {
        try {
            return time == null ? null : java.time.LocalTime.parse(time)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<ChatMessage> loadSourceMessages(long roomId) {
        return chatMessageRepository.findRecentMessagesForDraft(
                roomId,
                SenderType.PET,
                MessageType.TEXT,
                clock.instant().minus(SOURCE_WINDOW),
                PageRequest.of(0, MAX_SOURCE_MESSAGES));
    }

    /**
     * 최신순으로 받은 메시지를 시간순으로 되돌려 AI 에 넘긴다. AI 는 "내일 저녁"처럼
     * 앞선 발화를 참조하는 표현을 읽으므로 순서가 뒤집히면 추출이 틀어진다.
     */
    private AiDraftCommand toCommand(long roomId, List<ChatMessage> newestFirst) {
        List<ChatMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);

        List<AiDraftCommand.AiMessage> messages = chronological.stream()
                .map(m -> new AiDraftCommand.AiMessage(
                        String.valueOf(m.getSenderPetId()),
                        m.getBody(),
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                                m.getCreatedAt().atZone(SEOUL))))
                .toList();

        return new AiDraftCommand(
                String.valueOf(roomId),
                LocalDate.ofInstant(clock.instant(), SEOUL),
                messages);
    }
}
