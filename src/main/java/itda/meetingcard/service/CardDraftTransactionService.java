package itda.meetingcard.service;

import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.repository.CardDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초안 저장만 담당하는 트랜잭션 경계.
 *
 * <p>초안 생성 흐름에는 최대 5초짜리 AI HTTP 호출이 끼어 있다. 진입점에 트랜잭션을 걸면
 * 그 5초 동안 DB 커넥션이 아무 일도 안 하면서 붙잡힌다. Hikari 기본 풀이 10 이라 초안
 * 요청 몇 개가 동시에 몰리면 풀이 마르고, 카드와 무관한 채팅 폴링·로그인까지 커넥션을
 * 못 받는다. 그래서 트랜잭션은 실제 쓰기 한 줄만 감싼다.
 *
 * <p>기존 {@code PetCreationService}/{@code PetCreationTransactionService} 와
 * {@code ReportService}/{@code ReportCreateTransactionService} 가 같은 구조다.
 */
@Service
@RequiredArgsConstructor
public class CardDraftTransactionService {

    private final CardDraftRepository cardDraftRepository;

    @Transactional
    public CardDraft save(CardDraft draft) {
        return cardDraftRepository.save(draft);
    }
}
