package itda.meetingcard.ai;

import itda.meetingcard.domain.CardDraftFallbackReason;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MeetingDraftAiClient} 의 결정적 테스트 픽스처.
 *
 * <p>여섯 가지 AI 응답 시나리오를 네트워크 접근 없이 재현한다.
 * {@link #extract(AiDraftCommand)} 는 절대 예외를 던지지 않는다.
 */
public class FixtureMeetingDraftAiClient implements MeetingDraftAiClient {

    private List<AiExtractResponse> responses;
    private RuntimeException exceptionToThrow;
    private final ZoneId zoneId;

    public FixtureMeetingDraftAiClient() {
        this(ZoneId.of("Asia/Seoul"));
    }

    public FixtureMeetingDraftAiClient(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    // ── 준비 메서드 ──────────────────────────────────────────────

    /** 200 + [] —— 빈 배열 */
    public FixtureMeetingDraftAiClient prepareEmptyArray() {
        this.responses = List.of();
        this.exceptionToThrow = null;
        return this;
    }

    /** 전체 추출 */
    public FixtureMeetingDraftAiClient prepareFullExtraction(String meetingType, String date,
                                                             String time, String place) {
        this.responses = List.of(new AiExtractResponse(meetingType, date, time, place));
        this.exceptionToThrow = null;
        return this;
    }

    /** 알 수 없는 meeting_type */
    public FixtureMeetingDraftAiClient prepareUnknownType(String unknownType, String date,
                                                          String time, String place) {
        this.responses = List.of(new AiExtractResponse(unknownType, date, time, place));
        this.exceptionToThrow = null;
        return this;
    }

    /** 두 개 배열 요소 → 두 후보를 원래 순서대로 반환 */
    public FixtureMeetingDraftAiClient prepareTwoElements() {
        this.responses = new ArrayList<>();
        this.responses.add(new AiExtractResponse("WALK", "2026-07-31", "19:00", "중앙공원"));
        this.responses.add(new AiExtractResponse("PLAY", "2026-08-01", "10:00", "댕댕카페"));
        this.exceptionToThrow = null;
        return this;
    }

    /** TIMEOUT */
    public FixtureMeetingDraftAiClient prepareTimeout() {
        this.responses = null;
        this.exceptionToThrow = new HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException(
                "timeout", CardDraftFallbackReason.TIMEOUT);
        return this;
    }

    /** MODEL_ERROR (HTTP 502) */
    public FixtureMeetingDraftAiClient prepareModelError() {
        this.responses = null;
        this.exceptionToThrow = new HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException(
                "model error", CardDraftFallbackReason.MODEL_ERROR);
        return this;
    }

    /** 연결 실패 → MODEL_ERROR */
    public FixtureMeetingDraftAiClient prepareConnectionFailure() {
        this.responses = null;
        this.exceptionToThrow = new RuntimeException("Connection refused");
        return this;
    }

    /** 날짜만 있고 시간은 null */
    public FixtureMeetingDraftAiClient prepareDateOnly(String meetingType, String date,
                                                       String place) {
        this.responses = List.of(new AiExtractResponse(meetingType, date, null, place));
        this.exceptionToThrow = null;
        return this;
    }

    /** HOSPITAL */
    public FixtureMeetingDraftAiClient prepareHospitalExtraction(String date, String time,
                                                                 String place) {
        this.responses = List.of(new AiExtractResponse("HOSPITAL", date, time, place));
        this.exceptionToThrow = null;
        return this;
    }

    // ── 호출 관측 ────────────────────────────────────────────────

    /**
     * "메시지가 0~1개면 AI 를 부르지 않는다" 같은 계약은 결과만 봐서는 증명되지 않는다.
     * 실제로 안 불렸는지, 무엇을 넘겼는지 확인할 수단이 필요하다.
     */
    private int callCount;
    private AiDraftCommand lastCommand;

    public int callCount() {
        return callCount;
    }

    public AiDraftCommand lastCommand() {
        return lastCommand;
    }

    // ── extract() — 절대 예외를 던지지 않음 ──────────────────────

    @Override
    public AiDraftResult extract(AiDraftCommand command) {
        callCount++;
        lastCommand = command;

        // 예외 시나리오
        if (exceptionToThrow != null) {
            if (exceptionToThrow instanceof HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException e) {
                return AiDraftResult.fallback(e.getFallbackReason());
            }
            return AiDraftResult.fallback(CardDraftFallbackReason.MODEL_ERROR);
        }

        // 응답 매핑
        return mapResponse(responses);
    }

    // ── 응답 매핑 (MeetingCardAiAdapter 와 동일 로직) ───────────

    private AiDraftResult mapResponse(List<AiExtractResponse> raw) {
        return AiDraftResultMapper.map(raw, zoneId);
    }
}
