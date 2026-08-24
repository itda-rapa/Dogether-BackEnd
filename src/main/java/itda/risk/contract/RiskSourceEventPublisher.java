package itda.risk.contract;

/** 원천 도메인이 같은 DB 트랜잭션에서 위험 신호를 Outbox에 적재하는 진입점. */
public interface RiskSourceEventPublisher {
    void enqueue(RiskSourceEventCommand command);
}
