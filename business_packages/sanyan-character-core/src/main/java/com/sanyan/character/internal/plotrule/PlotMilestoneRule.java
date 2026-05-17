package com.sanyan.character.internal.plotrule;

import com.sanyan.character.internal.intimacy.IntimacyEvent;
import java.util.Optional;

/**
 * 剧情节点规则接口。
 *
 * <p>实现类用 @Component 注册，PlotMilestoneEngine 自动收集所有 Bean。
 *
 * <p>{@link #ruleId()} 默认按类名小写下划线，可被子类覆盖。
 */
public interface PlotMilestoneRule {

    /** 规则唯一 id，用于 milestones 表去重。默认按类名转 snake_case，去掉 Rule 后缀。 */
    default String ruleId() {
        return getClass().getSimpleName()
                .replaceFirst("Rule$", "")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    /**
     * 评估当前上下文是否触发本规则。
     * @return Optional 包装的 IntimacyEvent（包含 delta），或 empty 表示不触发
     */
    Optional<IntimacyEvent> evaluate(MessageContext ctx);
}
