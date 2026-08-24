package itda.chat.repository;

import itda.chat.domain.ChatMessageAttachment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageAttachmentRepository extends JpaRepository<ChatMessageAttachment, Long> {

    Optional<ChatMessageAttachment> findByMessageId(Long messageId);

    boolean existsByMediaId(Long mediaId);

    @Query("select a from ChatMessageAttachment a where a.message.id in :messageIds")
    List<ChatMessageAttachment> findAllByMessageIdIn(@Param("messageIds") Collection<Long> messageIds);
}
