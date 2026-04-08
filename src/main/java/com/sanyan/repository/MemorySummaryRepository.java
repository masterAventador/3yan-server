package com.sanyan.repository;

import com.sanyan.entity.MemorySummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MemorySummaryRepository extends JpaRepository<MemorySummary, Long> {
    List<MemorySummary> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);
}
