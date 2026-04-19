package com.sanyan.repository;

import com.sanyan.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdAndIdGreaterThanOrderByIdAsc(Long conversationId, Long afterId, Pageable pageable);
    List<Message> findByConversationIdAndIdLessThanOrderByIdDesc(Long conversationId, Long beforeId, Pageable pageable);
    List<Message> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);
    List<Message> findByConversationIdAndIdBetweenOrderByIdAsc(Long conversationId, Long startId, Long endId);

    @Query("""
        SELECT m FROM Message m
        WHERE m.conversationId IN :convIds
          AND m.id IN (
            SELECT MAX(m2.id) FROM Message m2
            WHERE m2.conversationId IN :convIds
            GROUP BY m2.conversationId
          )
    """)
    List<Message> findLastMessagePerConversation(@Param("convIds") Collection<Long> convIds);
}
