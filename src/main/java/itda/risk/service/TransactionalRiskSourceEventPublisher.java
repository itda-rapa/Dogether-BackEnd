package itda.risk.service;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSourceEventCommand;
import itda.risk.contract.RiskSourceEventPublisher;
import itda.risk.repository.RiskSignalOutboxJdbcRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionalRiskSourceEventPublisher implements RiskSourceEventPublisher {
    private final RiskSignalOutboxJdbcRepository outboxRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(RiskSourceEventCommand command) {
        outboxRepository.insertIfAbsent(RiskSignalEventV1.from(UUID.randomUUID(), command));
    }
}
