package itda.risk.policy;

import static org.assertj.core.api.Assertions.assertThat;

import itda.risk.contract.RiskSignalType;
import org.junit.jupiter.api.Test;

class RiskScorePolicyV1Test {
    private final RiskScorePolicy policy = new RiskScorePolicyV1();

    @Test
    void freezesUserBlockedFixtureWithVersion() {
        assertThat(policy.decide(RiskSignalType.USER_BLOCKED))
                .isEqualTo(new RiskScorePolicy.Decision(30, 1));
    }

    @Test
    void freezesGreetingExpiredFixtureWithVersion() {
        assertThat(policy.decide(RiskSignalType.GREETING_EXPIRED))
                .isEqualTo(new RiskScorePolicy.Decision(10, 1));
    }
}
