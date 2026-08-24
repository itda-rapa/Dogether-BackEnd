package itda.safety.domain;

public enum SafetyCaseStatus {
    OPEN,
    REVIEWING,
    DISMISSED,
    WARNING_RECORDED;

    public boolean isOpen() {
        return this == OPEN || this == REVIEWING;
    }
}
