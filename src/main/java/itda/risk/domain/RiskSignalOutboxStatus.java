package itda.risk.domain;

/** Outbox relay의 전송 상태. relay 구현은 별도 작업이다. */
public enum RiskSignalOutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    RETRY,
    FAILED
}
