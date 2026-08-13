package itda.petverification;

import itda.petverification.repository.PetVerificationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetVerificationBadgeService {
    private final PetVerificationRepository repository;
    public PetVerificationBadgeService(PetVerificationRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public Instant verifiedAt(Long petId) {
        return repository.findByPet_Id(petId).map(value -> value.getVerifiedAt()).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<Long, Instant> verifiedAtByPetIds(Collection<Long> petIds) {
        return repository.findBadgeRowsByPetIds(petIds).stream().collect(Collectors.toMap(
                PetVerificationRepository.PetVerificationBadgeRow::getPetId,
                PetVerificationRepository.PetVerificationBadgeRow::getVerifiedAt
        ));
    }
}
