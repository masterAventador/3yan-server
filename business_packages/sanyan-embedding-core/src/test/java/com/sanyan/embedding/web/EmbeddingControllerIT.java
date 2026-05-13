package com.sanyan.embedding.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.web.GlobalExceptionHandler;
import com.sanyan.embedding.EmbeddingApi;
import com.sanyan.embedding.dto.EmbedRequest;
import com.sanyan.embedding.internal.EmbeddingErrCode;
import com.sanyan.embedding.internal.InternalTokenInterceptor;
import com.sanyan.embedding.internal.WebMvcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task M2b：EmbeddingController @WebMvcTest 集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>token 缺失 → 401（拦截器拦下，Controller 未触发）</li>
 *   <li>token 错误 → 401</li>
 *   <li>token 正确 + 合法 body → 200 + 业务向量数据</li>
 *   <li>token 正确 + 空 list → 400（{@link jakarta.validation.constraints.NotEmpty}）</li>
 *   <li>token 正确 + adapter 抛 {@link EmbeddingErrCode#MODEL_NOT_READY}
 *       → 200 HTTP，body {@code success=false, code=6001}（{@link GlobalExceptionHandler} 处理）</li>
 * </ul>
 *
 * <p>mock 掉 {@link EmbeddingApi}（adapter 层），只验证 Controller + 拦截器 + 校验 + 全局异常处理。
 */
@WebMvcTest(controllers = EmbeddingController.class)
@Import({InternalTokenInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "sanyan.embedding.internal-token=test-token-correct")
class EmbeddingControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmbeddingApi embeddingApi;

    private static final String VALID_TOKEN = "test-token-correct";
    private static final String EMBED_PATH = "/embed";

    @Test
    void embed_shouldReturn401WhenTokenHeaderMissing() throws Exception {
        String body = objectMapper.writeValueAsString(new EmbedRequest(List.of("你好世界")));

        mockMvc.perform(post(EMBED_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(embeddingApi);
    }

    @Test
    void embed_shouldReturn401WhenTokenWrong() throws Exception {
        String body = objectMapper.writeValueAsString(new EmbedRequest(List.of("你好世界")));

        mockMvc.perform(post(EMBED_PATH)
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(embeddingApi);
    }

    @Test
    void embed_shouldReturn200AndVectorsWhenTokenCorrectAndBodyValid() throws Exception {
        float[] vec = new float[1024];
        vec[0] = 0.123f;
        vec[1023] = 0.456f;
        when(embeddingApi.embed(any())).thenReturn(List.of(vec));

        String body = objectMapper.writeValueAsString(new EmbedRequest(List.of("你好世界")));

        mockMvc.perform(post(EMBED_PATH)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dim").value(1024))
                .andExpect(jsonPath("$.data.vectors[0][0]").value(0.123))
                .andExpect(jsonPath("$.data.vectors[0][1023]").value(0.456))
                .andExpect(jsonPath("$.data.latencyMs").isNumber());
    }

    @Test
    void embed_shouldReturn400WhenTextsListEmpty() throws Exception {
        String body = objectMapper.writeValueAsString(new EmbedRequest(List.of()));

        mockMvc.perform(post(EMBED_PATH)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(410));

        verifyNoInteractions(embeddingApi);
    }

    @Test
    void embed_shouldReturnFailedRespWhenAdapterNotReady() throws Exception {
        when(embeddingApi.embed(any()))
                .thenThrow(new BusinessException(EmbeddingErrCode.MODEL_NOT_READY));

        String body = objectMapper.writeValueAsString(new EmbedRequest(List.of("你好世界")));

        mockMvc.perform(post(EMBED_PATH)
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(EmbeddingErrCode.MODEL_NOT_READY.getCode()))
                .andExpect(jsonPath("$.message").value(EmbeddingErrCode.MODEL_NOT_READY.getDefaultMessage()));
    }
}
