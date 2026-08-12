package itda.setlog.repository;

import itda.setlog.domain.SetlogUpload;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetlogUploadRepository extends JpaRepository<SetlogUpload, UUID> {
}
