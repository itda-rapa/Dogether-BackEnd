package itda.risk.contract;

/** Risk Kafka 계약 상수. 실제 발행은 Outbox relay 작업에서 구현한다. */
public final class RiskTopic {
    public static final String RISK_SIGNAL_TOPIC = "risk-signal-topic";

    private RiskTopic() {
    }

    public static String keyFor(RiskSignalEventV1 event) {
        return Long.toString(event.actorUserId());
    }
}
