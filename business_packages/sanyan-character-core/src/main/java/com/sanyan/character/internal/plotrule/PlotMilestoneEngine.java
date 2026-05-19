package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.intimacy.IntimacyEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 剧情节点规则引擎。
 *
 * <p>启动时 Spring 自动注入所有 {@link PlotMilestoneRule} Bean。
 * <p>每次 {@link #evaluate(MessageContext)}：
 * <ol>
 *   <li>跳过 ctx.triggeredRuleIds 中已触发的规则</li>
 *   <li>对剩下的规则调用 evaluate</li>
 *   <li>收集所有非空结果（多个规则可同时触发）</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class PlotMilestoneEngine {
    private final List<PlotMilestoneRule> rules;

    public List<IntimacyEvent> evaluate(MessageContext ctx) {
        return rules.stream()
                .filter(r -> !ctx.triggeredRuleIds().contains(r.ruleId()))
                .map(r -> r.evaluate(ctx))
                .flatMap(Optional::stream)
                .toList();
    }
}
