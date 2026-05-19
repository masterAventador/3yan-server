package com.sanyan.character.event;

import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipRepository;
import com.sanyan.character.internal.intimacy.IntimacyEvent;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.character.internal.intimacy.IntimacyRecordService;
import com.sanyan.character.internal.intimacy.ai.ConversationQualityEvaluator;
import com.sanyan.character.internal.intimacy.ai.QualityScoreResponse;
import com.sanyan.character.internal.plotrule.MessageContext;
import com.sanyan.character.internal.plotrule.PlotMilestoneEngine;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneEntity;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneId;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneRepository;
import com.sanyan.chat.ChatApi;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.event.MessagePersistedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 监听 chat-api {@link MessagePersistedEvent}，AFTER_COMMIT @Async 触发亲密度涨分链路：
 * <ol>
 *   <li>确保 relationship 存在（首次发消息时懒创建，H2 task 将抽成独立 service）</li>
 *   <li>累计 MESSAGE_SENT（每日封顶 50，由 IntimacyCalculator 控制）</li>
 *   <li>{@link PlotMilestoneEngine#evaluate} 跑剧情节点规则</li>
 *   <li>每 N 条用户消息触发 AI 对话质量评估</li>
 * </ol>
 *
 * <p>非用户消息（role != "user"）直接跳过。失败不重试不抛——记 ERROR 日志即可。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessagePersistedListener {

    private static final int RECENT_LIMIT = 32;

    private final IntimacyRecordService recordService;
    private final PlotMilestoneEngine plotEngine;
    private final ConversationQualityEvaluator evaluator;
    private final ChatApi chatApi;
    private final RelationshipRepository relRepo;
    private final RelationshipMilestoneRepository milestoneRepo;
    private final IntimacyProperties props;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagePersisted(MessagePersistedEvent event) {
        if (!SenderType.USER.equalsIgnoreCase(event.role())) {
            return;
        }

        try {
            // 1) 懒创建 relationship（H2 task 时抽到独立 service）
            ensureRelationship(event.userId(), event.characterId());

            // 2) MESSAGE_SENT 累计（IntimacyCalculator 内有每日封顶 50 逻辑）
            recordService.recordEvent(IntimacyEvent.messageSent(event.userId(), event.characterId()));

            // 3) 剧情规则评估
            List<MessageDto> recent = chatApi.listRecentByUser(event.userId(), RECENT_LIMIT);
            Set<String> triggered = milestoneRepo
                    .findAllByUserIdAndCharacterId(event.userId(), event.characterId())
                    .stream()
                    .map(RelationshipMilestoneEntity::getRuleId)
                    .collect(Collectors.toSet());
            var ctx = new MessageContext(event.userId(), event.characterId(), recent, triggered);

            for (IntimacyEvent plotEvent : plotEngine.evaluate(ctx)) {
                recordService.recordEvent(plotEvent);
                markMilestoneTriggered(event.userId(), event.characterId(), plotEvent.payloadStr());
            }

            // 4) AI 对话质量评估（每 N 条用户消息触发一次）
            long userMsgCount = recent.stream()
                    .filter(m -> SenderType.USER.equalsIgnoreCase(m.senderType()))
                    .count();
            int triggerN = props.getAi().getTriggerEveryNMessages();
            if (userMsgCount > 0 && userMsgCount % triggerN == 0) {
                QualityScoreResponse score = evaluator.score(recent);
                if (score.score() > 0) {
                    recordService.recordEvent(
                            IntimacyEvent.aiQuality(event.userId(), event.characterId(), score.score()));
                }
            }
        } catch (Exception e) {
            log.error("MessagePersistedListener 失败 userId={} characterId={} messageId={}",
                    event.userId(), event.characterId(), event.messageId(), e);
        }
    }

    /**
     * 把 PLOT 规则触发结果落到 relationship_milestones 表（去重表）。
     * 否则下次新消息 plotEngine 看到 triggeredRuleIds 仍为空，会再次触发同一规则。
     * 并发首次 INSERT 冲突时静默忽略——下次 onMessagePersisted 内 existsById 会返回 true。
     */
    private void markMilestoneTriggered(Long userId, Long characterId, String ruleId) {
        RelationshipMilestoneId id = new RelationshipMilestoneId(userId, characterId, ruleId);
        if (milestoneRepo.existsById(id)) {
            return;
        }
        RelationshipMilestoneEntity m = new RelationshipMilestoneEntity();
        m.setUserId(userId);
        m.setCharacterId(characterId);
        m.setRuleId(ruleId);
        try {
            milestoneRepo.save(m);
        } catch (Exception e) {
            log.debug("milestone 并发写入冲突（已存在），userId={} characterId={} ruleId={}",
                    userId, characterId, ruleId);
        }
    }

    /**
     * 懒创建 relationship 行。
     * H2 task 时将此逻辑抽到 {@code RelationshipFindOrCreateService}。
     * 并发首次 INSERT 冲突时静默忽略——下次 recordEvent 内 findByUserIdAndCharacterId 会成功。
     */
    private void ensureRelationship(Long userId, Long characterId) {
        if (relRepo.findByUserIdAndCharacterId(userId, characterId).isPresent()) {
            return;
        }
        RelationshipEntity r = new RelationshipEntity();
        r.setUserId(userId);
        r.setCharacterId(characterId);
        try {
            relRepo.save(r);
        } catch (Exception e) {
            log.debug("relationship 懒创建并发冲突（已存在），userId={} characterId={}", userId, characterId);
        }
    }
}
