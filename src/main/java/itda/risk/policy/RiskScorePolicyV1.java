package itda.risk.policy;

import itda.risk.contract.RiskSignalType;
import org.springframework.stereotype.Component;

@Component
public class RiskScorePolicyV1 implements RiskScorePolicy {
    static final int POLICY_VERSION = 1;
    static final int USER_BLOCKED_SCORE = 30;
    static final int GREETING_EXPIRED_SCORE = 10;

    @Override
    public Decision decide(RiskSignalType signalType) {
        int score = switch (signalType) {
            case USER_BLOCKED -> USER_BLOCKED_SCORE;
            case GREETING_EXPIRED -> GREETING_EXPIRED_SCORE;
        };
        return new Decision(score, POLICY_VERSION);
    }
}
