package com.sanyan.character.internal.plotrule;

import com.sanyan.chat.dto.MessageDto;
import java.util.List;
import java.util.Set;

/**
 * 剧情节点规则评估上下文。
 *
 * @param userId           当前用户
 * @param characterId      当前角色
 * @param recentMessages   最近 N 条消息（按时间正序，最新在尾部），用于规则判定
 * @param triggeredRuleIds 已经触发过的 rule_id 集合（来自 relationship_milestones 表），用于一次性规则去重
 */
public record MessageContext(
        Long userId,
        Long characterId,
        List<MessageDto> recentMessages,
        Set<String> triggeredRuleIds
) {}
