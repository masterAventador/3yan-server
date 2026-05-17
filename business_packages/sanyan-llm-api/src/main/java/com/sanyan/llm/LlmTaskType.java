package com.sanyan.llm;

/**
 * LLM 任务类型，决定走哪个 provider（由 LlmApi 内部按 type 路由）。
 *
 * <p>历史命名：S3 拆模块前叫 {@code LLMTaskType}（全大写 LLM 缩写）。
 * 本次重命名为 {@code LlmTaskType} 对齐 Java 规范（避免连续大写不易读 + 跟 Provider/Api 命名风格一致）。
 *
 * <ul>
 *   <li>{@link #USER_FACING}：用户对话，质量优先（具体 provider 由 llm-core 配置决定）</li>
 *   <li>{@link #BACKGROUND}：后台任务（摘要 / 档案抽取），成本优先（具体 provider 由 llm-core 配置决定）</li>
 * </ul>
 *
 * <p>注：本枚举只表达"业务意图"，不绑定具体厂商 / 模型。哪个 type 对应哪个 provider 是
 * llm-core 的实现细节，未来切换 provider（如换豆包另一型号 / 换 DeepSeek 别的版本）不影响本契约。
 */
public enum LlmTaskType {
    USER_FACING,
    BACKGROUND
}
