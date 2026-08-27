package itda.medicalsupport.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.medicalsupport.domain.MedicalSupportIngestionOutcome;
import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.ingestion.MedicalSupportSourceRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MedicalSupportIngestionService {

    private final MedicalSupportSourceRegistry registry;
    private final MedicalSupportSourceIngestionTransactionService transactionService;

    public MedicalSupportRevision ingest(String sourceKey) {
        return ingestResult(sourceKey).revision();
    }

    public MedicalSupportIngestionResult ingestResult(String sourceKey) {
        MedicalSupportIngestionResult result = transactionService.ingest(sourceKey);
        if (result.outcome() == MedicalSupportIngestionOutcome.FAILED) {
            throw new BusinessException(ErrorCode.MEDICAL_SUPPORT_SOURCE_FAILED);
        }
        return result;
    }

    public List<MedicalSupportIngestionResult> ingestPilot() {
        return registry.all().stream()
                .map(source -> transactionService.ingest(source.key()))
                .toList();
    }
}
