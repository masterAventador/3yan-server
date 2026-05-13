package com.sanyan.embedding.internal;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.sanyan.common.error.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 DJL（Deep Java Library）+ ONNX Runtime 引擎加载 BGE-M3 模型，
 * 实现 {@code List<String> → List<float[]>}。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>异步加载</b>：监听 {@link ApplicationReadyEvent}，在 Spring 启动完成后异步加载模型，
 *       避免阻塞 Spring 上下文 + 健康检查启动。模型下载 + 加载约 30~120 秒，期间
 *       {@code /actuator/health} 返回 {@code OUT_OF_SERVICE}（见
 *       {@link EmbeddingHealthIndicator}）。</li>
 *   <li><b>线程安全</b>：{@code volatile boolean ready} 表示模型加载完成；
 *       {@code Predictor} 实例在加载完成后初始化，调用方需自行串行化或外面做单 Predictor 节流。
 *       这里简化为"全局单 Predictor + synchronized 推理"——embedding-server 本身就是
 *       低 QPS 内部服务，单线程串行处理足够，避免 ONNX Runtime 内部多线程的复杂性。</li>
 *   <li><b>异常语义</b>：未 ready 抛 {@link EmbeddingErrCode#MODEL_NOT_READY}；
 *       推理过程出错抛 {@link EmbeddingErrCode#EMBEDDING_FAILED}。</li>
 * </ul>
 *
 * <p>模型 URL：{@code djl://ai.djl.huggingface.onnxruntime/BAAI/bge-m3}
 * （DJL 官方维护的 BGE-M3 ONNX 转写版本）。
 */
@Slf4j
@Component
public class BgeM3DjlAdapter {

    private static final int EXPECTED_DIM = 1024;
    private static final String DEFAULT_MODEL_URL =
            "djl://ai.djl.huggingface.onnxruntime/BAAI/bge-m3";

    private final String modelUrl;

    /** 模型加载完成标记。{@code volatile} 保证可见性。 */
    private volatile boolean ready = false;

    /** 模型加载后单例的 Predictor；{@code synchronized embed()} 串行化使用。 */
    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;

    public BgeM3DjlAdapter(
            @Value("${sanyan.embedding.model.url:" + DEFAULT_MODEL_URL + "}") String modelUrl) {
        this.modelUrl = modelUrl;
    }

    /**
     * 模型加载入口。监听 {@link ApplicationReadyEvent} 异步触发——这样
     * Spring 上下文先启动 + 端口先 listen，让 actuator 和探活先可达，
     * 模型在后台慢慢加载。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void loadModel() {
        long start = System.currentTimeMillis();
        log.info("开始加载 BGE-M3 模型: url={}", modelUrl);
        try {
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls(modelUrl)
                    .build();
            this.model = criteria.loadModel();
            this.predictor = this.model.newPredictor();
            this.ready = true;
            log.info("BGE-M3 模型加载完成: 耗时={}ms", System.currentTimeMillis() - start);
        } catch (Throwable e) {
            log.error("BGE-M3 模型加载失败，服务保持 not-ready：{}", e.getMessage(), e);
            this.ready = false;
        }
    }

    /** 是否已 ready（模型加载完成）。供 health indicator 调用。 */
    public boolean isReady() {
        return ready;
    }

    /**
     * 把一批文本转为向量。
     *
     * @throws BusinessException {@link EmbeddingErrCode#MODEL_NOT_READY} 模型未加载完成
     * @throws BusinessException {@link EmbeddingErrCode#EMBEDDING_FAILED} 推理过程异常
     */
    public synchronized List<float[]> embed(List<String> texts) {
        if (!ready || predictor == null) {
            throw new BusinessException(EmbeddingErrCode.MODEL_NOT_READY);
        }
        try {
            List<float[]> out = new ArrayList<>(texts.size());
            for (String text : texts) {
                float[] vec = predictor.predict(text);
                if (vec == null || vec.length != EXPECTED_DIM) {
                    log.error("BGE-M3 推理返回非法向量: text 截断={}, dim={}",
                            text == null ? "null" : text.substring(0, Math.min(text.length(), 32)),
                            vec == null ? -1 : vec.length);
                    throw new BusinessException(EmbeddingErrCode.EMBEDDING_FAILED);
                }
                out.add(vec);
            }
            return out;
        } catch (BusinessException e) {
            throw e;
        } catch (Throwable e) {
            log.error("BGE-M3 推理异常：{}", e.getMessage(), e);
            throw new BusinessException(EmbeddingErrCode.EMBEDDING_FAILED);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (predictor != null) {
            try {
                predictor.close();
            } catch (Exception ignored) {
                // 关闭异常忽略
            }
        }
        if (model != null) {
            try {
                model.close();
            } catch (Exception ignored) {
                // 关闭异常忽略
            }
        }
    }
}
