package com.sanyan.character.event;

import com.sanyan.character.internal.plotrule.RelationshipMilestoneEntity;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneId;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听 StageTransitionEvent → 写 relationship_milestones（防重）+ 发 StageEntryStoryEvent。
 *
 * <p>剧情演出文案按目标 stage 选取（STORIES[0] 空字符串保留位置，stage 0 不演出）。
 * <p>milestones 表的 rule_id 格式 "stage_entry_&lt;N&gt;"。
 */
@Slf4j
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
        int toStage = event.toStage();

        // 边界检查前置：越界直接返回，不写 milestone 也不发事件
        if (toStage < 0 || toStage >= STORIES.length) {
            log.warn("toStage {} 超出 STORIES 范围，跳过", toStage);
            return;
        }

        String story = STORIES[toStage];
        if (story.isBlank()) {
            // stage 0 默认无剧情演出，直接跳过（不写 milestone，不发事件）
            return;
        }

        String ruleId = "stage_entry_" + toStage;
        var id = new RelationshipMilestoneId(event.userId(), event.characterId(), ruleId);
        if (milestoneRepo.existsById(id)) {
            return;
        }

        RelationshipMilestoneEntity m = new RelationshipMilestoneEntity();
        m.setUserId(event.userId());
        m.setCharacterId(event.characterId());
        m.setRuleId(ruleId);
        milestoneRepo.save(m);

        publisher.publishEvent(new StageEntryStoryEvent(
                event.userId(), event.characterId(), toStage, story));
    }
}
