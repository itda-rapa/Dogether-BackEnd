package itda.risk.service;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.policy.RiskScorePolicy;
import itda.risk.repository.RiskSignalEventJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RiskSignalIngestionService {
    private final RiskScorePolicy scorePolicy;
    private final RiskSignalEventJdbcRepository repository;

    @Transactional
    public Result ingest(RiskSignalEventV1 event) {
        boolean inserted = repository.insertIfAbsent(event, scorePolicy.decide(event.signalType()));
        return inserted ? Result.INSERTED : Result.DUPLICATE;
    }

    public enum Result {
        INSERTED,
        DUPLICATE
    }
}
