package com.sanyan.character.internal.stage;

import com.sanyan.character.event.StageTransitionEvent;
import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 检测亲密度涨分后是否跨越阶段边界，跨阶段则更新 stage 并发布 StageTransitionEvent。
 * 调用方：IntimacyRecordService（D5）。
 */
@Service
@RequiredArgsConstructor
public class StageTransitionDetectService {

    private final StageDefinition stageDef;
    private final RelationshipRepository repo;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public void maybeTransition(RelationshipEntity rel, int newScore) {
        int newStage = stageDef.stageFor(newScore);
        int oldStage = rel.getCurrentStage();
        // 只升不降：阈值临时改高（dogfood 调参 / 运营回退）后 stageFor 可能算出更小值，
        // 此时不应把 DB stage 降级 + 发"退回去"的 StageTransitionEvent。
        if (newStage <= oldStage) return;

        rel.setCurrentStage((short) newStage);
        repo.save(rel);
        publisher.publishEvent(new StageTransitionEvent(
                rel.getUserId(), rel.getCharacterId(), oldStage, newStage));
    }
}
