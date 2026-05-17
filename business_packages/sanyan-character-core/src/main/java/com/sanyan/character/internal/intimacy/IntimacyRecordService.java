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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 亲密度涨分核心入口。
 *
 * <p>流程：
 * <ol>
 *   <li>findOrThrow(uid, cid) 拿 RelationshipEntity</li>
 *   <li>calculator.compute(event) → delta</li>
 *   <li>累加 score 并 save（乐观锁）</li>
 *   <li>若是 MESSAGE_SENT 且 delta>0：dailyCounter.incr 累计今日消耗</li>
 *   <li>写 IntimacyLogEntity 审计（delta==0 时 reason='CAPPED'）</li>
 *   <li>stageTransition.maybeTransition 检测阶段切换</li>
 *   <li>publishEvent(IntimacyChangedEvent)</li>
 * </ol>
 *
 * <p>OptimisticLockingFailureException 时 retry 3 次；3 次仍失败抛 BusinessException(INTIMACY_CONCURRENT_UPDATE)。
 */
@Service
@RequiredArgsConstructor
public class IntimacyRecordService {

    private static final int MAX_RETRY = 3;

    private final RelationshipRepository relRepo;
    private final IntimacyLogRepository logRepo;
    private final IntimacyCalculator calculator;
    private final DailyBehaviorCounter dailyCounter;
    private final StageTransitionDetectService stageTransition;
    private final ApplicationEventPublisher publisher;
    private final StageDefinition stageDef;

    @Transactional
    public void recordEvent(IntimacyEvent event) {
        OptimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                doRecord(event);
                return;
            } catch (OptimisticLockingFailureException e) {
                last = e;
            }
        }
        throw new BusinessException(CharacterErrCode.INTIMACY_CONCURRENT_UPDATE);
    }

    private void doRecord(IntimacyEvent event) {
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
