package itda.medicalsupport.repository;
import itda.medicalsupport.domain.MedicalSupportIngestionAttempt;
import itda.medicalsupport.domain.MedicalSupportIngestionOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MedicalSupportIngestionAttemptRepository extends JpaRepository<MedicalSupportIngestionAttempt, Long> {
    long countBySourceKeyAndOutcome(String sourceKey, MedicalSupportIngestionOutcome outcome);
}
