package com.sanyan.repository;

import com.sanyan.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long afterId, Pageable pageable);
    List<Message> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long beforeId, Pageable pageable);
    List<Message> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
