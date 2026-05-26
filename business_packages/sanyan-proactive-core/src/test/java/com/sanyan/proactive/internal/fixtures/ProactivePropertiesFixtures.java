package com.sanyan.proactive.internal.fixtures;

import com.sanyan.proactive.internal.ProactiveProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * ProactiveProperties Object Mother（spec §11）。
 *
 * <p>默认值约定（plan 附录 §H）：
 * <ul>
 *   <li>stage 0：陌生人 — morning=false, night=false（不发早晚安）</li>
 *   <li>stage 1：朋友  — morning=false, night=true</li>
 *   <li>stage 2：暧昧  — morning=true, night=true</li>
 *   <li>stage 3：恋人  — morning=true, night=true</li>
 *   <li>stage 4：老夫老妻 — morning=true, night=true</li>
 * </ul>
 * scatterWindowMinutes=30，greeting cron 使用 ProactiveProperties 默认值（8:00 / 22:30）。
 */
public final class ProactivePropertiesFixtures {

    private ProactivePropertiesFixtures() {}

    /**
     * 返回填好 scenesByStage 的默认 ProactiveProperties（不经 Spring 绑定，直接构造，单测用）。
     */
    public static ProactiveProperties defaults() {
        ProactiveProperties props = new ProactiveProperties();
        props.setScatterWindowMinutes(30);

        Map<Integer, ProactiveProperties.SceneFlags> scenes = new HashMap<>();

        // stage 0: 陌生人，不发早晚安
        ProactiveProperties.SceneFlags stage0 = new ProactiveProperties.SceneFlags();
        stage0.setMorning(false);
        stage0.setNight(false);
        stage0.setRecall(false);
        stage0.setEventFollowup(false);
        stage0.setEmotionCare(false);
        scenes.put(0, stage0);

        // stage 1: 朋友，只发晚安
        ProactiveProperties.SceneFlags stage1 = new ProactiveProperties.SceneFlags();
        stage1.setMorning(false);
        stage1.setNight(true);
        stage1.setRecall(true);
        stage1.setEventFollowup(false);
        stage1.setEmotionCare(false);
        scenes.put(1, stage1);

        // stage 2: 暧昧，早晚安都发
        ProactiveProperties.SceneFlags stage2 = new ProactiveProperties.SceneFlags();
        stage2.setMorning(true);
        stage2.setNight(true);
        stage2.setRecall(true);
        stage2.setEventFollowup(true);
        stage2.setEmotionCare(true);
        scenes.put(2, stage2);

        // stage 3: 恋人，早晚安都发
        ProactiveProperties.SceneFlags stage3 = new ProactiveProperties.SceneFlags();
        stage3.setMorning(true);
        stage3.setNight(true);
        stage3.setRecall(true);
        stage3.setEventFollowup(true);
        stage3.setEmotionCare(true);
        scenes.put(3, stage3);

        // stage 4: 老夫老妻，早晚安都发
        ProactiveProperties.SceneFlags stage4 = new ProactiveProperties.SceneFlags();
        stage4.setMorning(true);
        stage4.setNight(true);
        stage4.setRecall(true);
        stage4.setEventFollowup(true);
        stage4.setEmotionCare(true);
        scenes.put(4, stage4);

        props.setScenesByStage(scenes);
        return props;
    }
}
