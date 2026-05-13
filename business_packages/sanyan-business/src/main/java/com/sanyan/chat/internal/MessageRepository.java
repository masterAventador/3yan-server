package com.sanyan.chat.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long afterId, Pageable pageable);
    List<MessageEntity> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long beforeId, Pageable pageable);
    List<MessageEntity> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
