package itda.risk.service;

import itda.risk.config.RiskSignalConsumerProperties;
import itda.risk.repository.RiskSignalEventJdbcRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskSignalAggregateService {
    private final RiskSignalEventJdbcRepository repository;
    private final RiskSignalConsumerProperties properties;

    public RiskSignalAggregate forActor(long actorUserId, Instant fromInclusive, Instant toExclusive) {
        validate(actorUserId, fromInclusive, toExclusive);
        return repository.aggregateForActor(actorUserId, fromInclusive, toExclusive);
    }

    public RiskSignalAggregate forTarget(long targetUserId, Instant fromInclusive, Instant toExclusive) {
        validate(targetUserId, fromInclusive, toExclusive);
        return repository.aggregateForTarget(targetUserId, fromInclusive, toExclusive);
    }

    public RiskSignalAggregate forActorAndTarget(
            long actorUserId, long targetUserId, Instant fromInclusive, Instant toExclusive
    ) {
        validate(actorUserId, fromInclusive, toExclusive);
        if (targetUserId <= 0) {
            throw new IllegalArgumentException("targetUserId must be positive");
        }
        return repository.aggregateForActorAndTarget(
                actorUserId, targetUserId, fromInclusive, toExclusive);
    }

    private void validate(long userId, Instant fromInclusive, Instant toExclusive) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("aggregation range must satisfy from < to");
        }
        Duration range = Duration.between(fromInclusive, toExclusive);
        if (range.compareTo(properties.maxAggregationRange()) > 0) {
            throw new IllegalArgumentException("aggregation range exceeds configured maximum");
        }
    }
}
