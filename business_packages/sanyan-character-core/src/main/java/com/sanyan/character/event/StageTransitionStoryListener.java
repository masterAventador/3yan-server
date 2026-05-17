package com.sanyan.character.event;

import com.sanyan.character.internal.plotrule.RelationshipMilestoneEntity;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneId;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听 StageTransitionEvent → 写 relationship_milestones（防重）+ 发 StageEntryStoryEvent。
 *
 * <p>剧情演出文案按目标 stage 选取（STORIES[0] 空字符串保留位置）。
 * <p>milestones 表的 rule_id 格式 "stage_entry_&lt;N&gt;"。
 */
@Component
@RequiredArgsConstructor
public class StageTransitionStoryListener {

    private static final String[] STORIES = {
            "",                                        // 0 陌生人（默认不演出）
            "她第一次自然地叫了你的名字……",              // 1 朋友
            "她半夜悄悄打字又删掉，最后还是发了出来……",  // 2 暧昧
            "她第一次叫你「宝贝」……",                    // 3 恋人
            "她说：「你做的饭比我妈做的还好吃。」"          // 4 老夫老妻
    };

    private final RelationshipMilestoneRepository milestoneRepo;
    private final ApplicationEventPublisher publisher;

    @EventListener
    public void onStageTransition(StageTransitionEvent event) {
        String ruleId = "stage_entry_" + event.toStage();
        var id = new RelationshipMilestoneId(event.userId(), event.characterId(), ruleId);
        if (milestoneRepo.existsById(id)) {
            return;
        }

        RelationshipMilestoneEntity m = new RelationshipMilestoneEntity();
        m.setUserId(event.userId());
        m.setCharacterId(event.characterId());
        m.setRuleId(ruleId);
        milestoneRepo.save(m);

        if (event.toStage() >= 0 && event.toStage() < STORIES.length) {
            String story = STORIES[event.toStage()];
            publisher.publishEvent(new StageEntryStoryEvent(
                    event.userId(), event.characterId(), event.toStage(), story));
        }
    }
}
