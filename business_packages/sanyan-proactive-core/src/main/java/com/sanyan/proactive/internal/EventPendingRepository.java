package com.sanyan.proactive.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventPendingRepository extends JpaRepository<EventPendingEntity, Long> {

    List<EventPendingEntity> findByStatusAndScheduledAtBefore(EventStatus status, Instant time);

    /**
     * 领取到期的 SCHEDULED 事件，FOR UPDATE SKIP LOCKED 防多 scheduler 实例并发重复领取（spec §5.1）。
     * 调用方应在同一事务内随即把领到的行标 PROCESSING。
     */
    @Query(value = "SELECT * FROM events_pending WHERE status = 'SCHEDULED' AND scheduled_at <= :now "
            + "ORDER BY scheduled_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<EventPendingEntity> findDueForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
