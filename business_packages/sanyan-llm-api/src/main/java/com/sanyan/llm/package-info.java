/**
 * sanyan-llm-api：LLM 域对内契约（java-backend §2.2）。
 *
 * <p>只含接口（LlmApi）+ DTO（ChatMessage）+ enum（LlmTaskType）。零依赖。
 * 实现在 sanyan-llm-core，通过 LlmApiImpl 暴露。
 */
package com.sanyan.llm;
