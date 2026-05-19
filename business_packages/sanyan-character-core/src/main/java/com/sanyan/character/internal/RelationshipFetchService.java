package com.sanyan.character.internal;

import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.character.internal.intimacy.ConsecutiveLoginService;
import com.sanyan.character.internal.intimacy.IntimacyEvent;
import com.sanyan.character.internal.intimacy.IntimacyRecordService;
import com.sanyan.character.internal.stage.StageDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * GET /api/relationships/me 业务编排：
 * <ol>
 *   <li>findOrCreate Relationship（懒创建）</li>
 *   <li>consecutiveLoginService.recordLogin —— 当日首次返回 isFirstToday=true 则触发 DAILY_LOGIN 涨分</li>
 *   <li>重新读 Relationship（涨分后 score / stage 可能变）</li>
 *   <li>拼 RelationshipDto 返回（含 stage name / next_threshold / percent）</li>
 * </ol>
 * <p>日级幂等：同日重复调用不重复涨分（recordLogin 内部判断 last_date）。
 *
 * <p><b>本方法没有 @Transactional —— 关键事务边界：</b>
 * findOrCreateService.findOrCreate 自带 @Transactional（save 在它自己事务内 commit），
 * intimacyRecordService.recordEvent 内部 IntimacyRecordTransaction.doRecord 是 REQUIRES_NEW。
 * 如果 fetchMyRelationship 外包一层 @Transactional，REQUIRES_NEW 会挂起外层，在新事务里查不到
 * 外层未提交的 relationship 行 → 抛 RELATIONSHIP_NOT_FOUND。
 * 不加 @Transactional 让两步各自独立提交，是 K2 dogfood 暴露并修复的设计。
 */
@Service
@RequiredArgsConstructor
public class RelationshipFetchService {

    private final RelationshipFindOrCreateService findOrCreateService;
    private final ConsecutiveLoginService loginService;
    private final IntimacyRecordService intimacyRecordService;
    private final StageDefinition stageDef;

    public RelationshipDto fetchMyRelationship(Long userId, Long characterId) {
        RelationshipEntity rel = findOrCreateService.findOrCreate(userId, characterId);

        var login = loginService.recordLogin(userId, LocalDate.now());
        if (login.isFirstToday()) {
            intimacyRecordService.recordEvent(
                    IntimacyEvent.dailyLogin(userId, characterId, login.streak()));
            // 重新读取保证 score 是 recordEvent 后的最新值
            rel = findOrCreateService.findOrCreate(userId, characterId);
        }

        int stage = rel.getCurrentStage();
        int nextThreshold = stageDef.nextThresholdOf(stage);
        int prevThreshold = stage == 0 ? 0 : stageDef.nextThresholdOf(stage - 1);
        double percent;
        if (nextThreshold == Integer.MAX_VALUE) {
            percent = 1.0;
        } else {
            int range = nextThreshold - prevThreshold;
            percent = range == 0 ? 0.0 : (double) (rel.getIntimacyScore() - prevThreshold) / range;
            percent = Math.max(0.0, Math.min(1.0, percent));
        }

        return new RelationshipDto(
                userId, characterId,
                rel.getIntimacyScore(), stage,
                stageDef.nameOf(stage), nextThreshold, percent);
    }
}
