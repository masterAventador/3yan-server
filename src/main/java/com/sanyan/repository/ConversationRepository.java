package com.sanyan.repository;

import com.sanyan.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    List<Conversation> findByUserIdOrderByLastMessageAtDesc(Long userId);
    Optional<Conversation> findByUserIdAndCharacterId(Long userId, Long characterId);
}
