package itda.medicalsupport.service;

import itda.medicalsupport.domain.MedicalSupportIngestionAttempt;
import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.repository.MedicalSupportIngestionAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class MedicalSupportAttemptService {
    private final MedicalSupportIngestionAttemptRepository attempts;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(String key, String url, String contentType, String hash, MedicalSupportRevision revision) { attempts.save(MedicalSupportIngestionAttempt.succeeded(key, url, contentType, hash, revision)); }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String key, String url, String reason, String contentType, String hash) { attempts.save(MedicalSupportIngestionAttempt.failed(key, url, reason, contentType, hash)); }
}
