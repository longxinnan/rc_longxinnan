package com.rc.longxinnan.repository;

import com.rc.longxinnan.domain.OutboxNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxNotificationRepository extends JpaRepository<OutboxNotification, Long> {

    /** 幂等去重查找（由唯一约束 uk_provider_event 兜底并发）。 */
    Optional<OutboxNotification> findByProviderAndEventId(String provider, String eventId);

    /**
     * 轮询器认领批次。短事务：认领提交即释放行锁，随后逐行在各自事务内投递与更新。
     *
     * <p>注意：必须显式 {@code @Transactional}（非 readOnly），
     * MySQL 的 {@code FOR UPDATE} 不允许在只读事务中执行；
     * Spring Data 默认给查询方法加 readOnly=true，会覆盖则无法使用 SKIP LOCKED。
     */
    @Transactional
    @Query(value = """
            SELECT * FROM outbox_notification
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at ASC
            LIMIT :batch
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxNotification> claimPending(@Param("now") Instant now, @Param("batch") int batch);
}
