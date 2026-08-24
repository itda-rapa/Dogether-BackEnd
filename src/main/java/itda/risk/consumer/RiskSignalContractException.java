package itda.risk.consumer;

public class RiskSignalContractException extends RuntimeException {
    private final Reason reason;

    public RiskSignalContractException(Reason reason) {
        super("Invalid risk signal contract: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MALFORMED_PAYLOAD,
        INVALID_CONTRACT,
        INVALID_KEY,
        FUTURE_OCCURRED_AT
    }
}
