package com.sanyan.proactive.internal;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动消息频率 / 时机参数（spec §6.3 全参数化，dogfood 改 yml 不改代码）。
 * 结构见 plan 附录 H。stage 下标 0..4 对应陌生人/朋友/暧昧/恋人/老夫老妻。
 * <p>
 * 注册方式：由 ProactiveConfig（@Configuration + @EnableConfigurationProperties）激活，
 * 本类不加 @Component。
 * <p>
 * <b>注意 {@code scenesByStage} 的 null 语义：</b>
 * {@code scenesByStage} 是一个稀疏 Map，对于 application.yml 中未配置的 stage，
 * {@code scenesByStage.get(stage)} 返回 {@code null}（而非空 {@link SceneFlags}）。
 * 调用方（如 J1 FrequencyGate）在读取前必须做 null guard，例如：
 * <pre>{@code
 * SceneFlags flags = props.getScenesByStage().get(stage);
 * if (flags == null) return false;  // 未配置 stage 视为全部场景关闭
 * }</pre>
 */
@ConfigurationProperties("sanyan.proactive")
@Data
public class ProactiveProperties {

    private Greeting greeting = new Greeting();
    private Recall recall = new Recall();
    private QuietHours quietHours = new QuietHours();
    private int scatterWindowMinutes = 30;
    /** 每 stage 每日主动消息上限（硬顶）；下标 = stage。 */
    private List<Integer> dailyCapByStage = new ArrayList<>(List.of(2, 3, 4, 5, 6));
    /** 每 stage 允许的场景开关；key = stage。 */
    private Map<Integer, SceneFlags> scenesByStage = new HashMap<>();
    /** 互动退避：自用户最后回话以来未回应的主动消息达到此数，停发 A_GREETING（放行 B_RECALL）。0 关闭退避。 */
    private int unansweredGreetingBackoffThreshold = 3;

    @Data
    public static class Greeting {
        private String morningCron = "0 0 8 * * *";
        private String nightCron = "0 30 22 * * *";
    }

    @Data
    public static class Recall {
        /** 失联召回阶梯阈值（小时）：24h / 3天 / 7天。 */
        private List<Integer> thresholdsHours = new ArrayList<>(List.of(24, 72, 168));
    }

    @Data
    public static class QuietHours {
        /** 免打扰起始小时（含）。默认 23。 */
        private int start = 23;
        /** 免打扰结束小时（不含）。默认 8（早安窗口起点）。 */
        private int end = 8;
    }

    @Data
    public static class SceneFlags {
        private boolean morning;
        private boolean night;
        private boolean recall;
        private boolean eventFollowup;
        private boolean emotionCare;
    }
}
