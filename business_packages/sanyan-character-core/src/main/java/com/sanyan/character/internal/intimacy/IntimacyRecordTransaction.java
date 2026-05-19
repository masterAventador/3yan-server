package com.sanyan.character.internal.intimacy;

import com.sanyan.character.event.IntimacyChangedEvent;
import com.sanyan.character.internal.CharacterErrCode;
import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipRepository;
import com.sanyan.character.internal.stage.StageDefinition;
import com.sanyan.character.internal.stage.StageTransitionDetectService;
import com.sanyan.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 亲密度涨分的"单次事务"内层。
 *
 * <p>独立 Bean 持有 {@link #doRecord} 是为让 Spring AOP 代理生效：
 * <ul>
 *   <li>{@link IntimacyRecordService#recordEvent} 在事务**外**做 retry 循环</li>
 *   <li>每次 retry 调用本类 {@link #doRecord}，由代理开启 <b>新事务</b>（{@code REQUIRES_NEW}）</li>
 * </ul>
 *
 * <p>如果不拆出独立 Bean，把 {@code @Transactional} 加在
 * {@code IntimacyRecordService} 的 private 方法上，self-invocation 不走代理，事务注解失效。
 *
 * <p>{@code REQUIRES_NEW}：保证每次 retry 都拿到全新事务，OptimisticLockingFailureException
 * 抛出后旧事务回滚释放锁，下一次 retry 才能读到最新 version。
 */
@Service
@RequiredArgsConstructor
public class IntimacyRecordTransaction {

    private final RelationshipRepository relRepo;
    private final IntimacyLogRepository logRepo;
    private final IntimacyCalculator calculator;
    private final DailyBehaviorCounter dailyCounter;
    private final StageTransitionDetectService stageTransition;
    private final ApplicationEventPublisher publisher;
    private final StageDefinition stageDef;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void doRecord(IntimacyEvent event) {
        RelationshipEntity rel = relRepo.findByUserIdAndCharacterId(event.userId(), event.characterId())
                .orElseThrow(() -> new BusinessException(CharacterErrCode.RELATIONSHIP_NOT_FOUND));

        int delta = calculator.compute(event);
        int oldScore = rel.getIntimacyScore();
        int newScore = oldScore + delta;
        rel.setIntimacyScore(newScore);
        relRepo.save(rel);

        if (event.type() == IntimacyEvent.Type.MESSAGE_SENT && delta > 0) {
            dailyCounter.incr(event.userId(), delta);
        }

        String reason = delta == 0 ? "CAPPED" : reasonOf(event);

        IntimacyLogEntity log = new IntimacyLogEntity();
        log.setUserId(event.userId());
        log.setCharacterId(event.characterId());
        log.setDelta(delta);
        log.setReason(reason);
        log.setNewScore(newScore);
        log.setNewStage((short) stageDef.stageFor(newScore));
        logRepo.save(log);

        stageTransition.maybeTransition(rel, newScore);

        publisher.publishEvent(new IntimacyChangedEvent(
                event.userId(), event.characterId(), oldScore, newScore, delta, reason));
    }

    private static String reasonOf(IntimacyEvent event) {
        return switch (event.type()) {
            case MESSAGE_SENT     -> "MESSAGE_SENT";
            case DAILY_LOGIN      -> "DAILY_LOGIN";
            case PLOT_MILESTONE   -> "PLOT:" + event.payloadStr();
            case AI_QUALITY_BONUS -> "AI_QUALITY";
        };
    }
}
