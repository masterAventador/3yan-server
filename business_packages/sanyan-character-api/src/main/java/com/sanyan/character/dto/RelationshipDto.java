package com.sanyan.character.dto;

/**
 * Relationship 跨模块 DTO（user × character 之间的关系状态）。
 *
 * @param userId               用户 id
 * @param characterId          角色 id
 * @param intimacyScore        亲密度总分
 * @param currentStage         当前阶段（0..4）
 * @param currentStageName     阶段中文名（"陌生人" / "朋友" / "暧昧" / "恋人" / "老夫老妻"）
 * @param nextStageThreshold   下一阶段起始阈值（4 阶段时为 Integer.MAX_VALUE）
 * @param percentToNextStage   距下一阶段进度（0.0-1.0）
 */
public record RelationshipDto(
        Long userId,
        Long characterId,
        int intimacyScore,
        int currentStage,
        String currentStageName,
        int nextStageThreshold,
        double percentToNextStage) {}
