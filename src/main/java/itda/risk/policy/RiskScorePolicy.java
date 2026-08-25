package itda.risk.policy;

import itda.risk.contract.RiskSignalType;

public interface RiskScorePolicy {
    Decision decide(RiskSignalType signalType);

    record Decision(int score, int policyVersion) {
    }
}
