package com.sanyan.character.internal.stage;

import com.sanyan.character.event.StageTransitionEvent;
import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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

    public void maybeTransition(RelationshipEntity rel, int newScore) {
        int newStage = stageDef.stageFor(newScore);
        int oldStage = rel.getCurrentStage();
        if (newStage == oldStage) return;

        rel.setCurrentStage((short) newStage);
        repo.save(rel);
        publisher.publishEvent(new StageTransitionEvent(
                rel.getUserId(), rel.getCharacterId(), oldStage, newStage));
    }
}
