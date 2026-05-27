package com.sanyan.proactive.internal.generator;

import com.sanyan.proactive.internal.EventType;

import java.util.List;

/** 主动消息文案生成器：每种 EventType 一个实现（Phase K）。返回多条 segment（按 typing 节奏逐条投递）。 */
public interface ProactiveGenerator {

    /** 本生成器负责的场景类型。 */
    EventType supportsType();

    /** 生成多条消息 segment。 */
    List<String> generate(GenerateContext ctx);
}
