package com.sanyan.chat.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.chat.ws.WsNewMessage;
import com.sanyan.common.ws.SessionManager;
import com.sanyan.push.PushApi;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 主动消息 4 层可靠投递（spec §7.1）。<b>仅服务主动消息</b>，不重构 Plan 1 普通对话直发链路。
 *
 * <p>单条 segment 投递流程：
 * <ol>
 *   <li>落库 AI message（{@link MessageService#saveAiMessage}）拿 messageId</li>
 *   <li><b>L1</b> 在线直推：{@link SessionManager#getSession} 拿到 open session → WS 推 {@link WsNewMessage}</li>
 *   <li><b>L2</b> ACK 超时兜底：注册 {@link CompletableFuture} + {@code orTimeout(ackTimeoutMs)}；
 *       收到 ACK → 完成（视为 sent）；超时 → 转 L3</li>
 *   <li><b>L3</b> 离线推送：{@link PushApi#pushToUser}（本期 APNs 占位）。失败不抛断流程，靠 L4 sync 兜底（spec §5.1）</li>
 *   <li><b>L4</b> 启动拉取兜底：客户端下次打开 app sync /api/messages（Plan 1 已有，不在本类）</li>
 * </ol>
 *
 * <p>L1 用 getSession() 不用 isOnline()（spec §7.1）：单实例下能拿到 open session 更可靠，
 * TCP 半开极端情况由 L2 ACK 兜底——送达正确性靠 ACK，不靠在线标记精度。
 *
 * <p>TODO（消除 WS 推送重复）：{@link #pushFrame} 与 {@code ChatWebSocketHandler.sendObject}
 * 同构，待后续下沉到 {@code SessionManager.send(userId, json)}，本 task 不动基础层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final MessageService messageService;
    private final SessionManager sessionManager;
    private final PushApi pushApi;
    private final ObjectMapper objectMapper;

    /**
     * L2 ACK 等待超时毫秒数；可经 yml 配置 / 测试用 ReflectionTestUtils.setField 覆盖。
     * 默认 5000ms；测试里设为 150ms 加速超时用例。
     */
    @Value("${sanyan.delivery.ack-timeout-ms:5000}")
    private long ackTimeoutMs = 5000;

    /** messageId → 等待 ACK 的 future。在线推送后注册，confirmAck 完成，超时/离线移除。 */
    private final Map<Long, CompletableFuture<Void>> pendingAcks = new ConcurrentHashMap<>();

    /** 主动消息推送通知标题，单角色 MVP 硬编码。 */
    private static final String PUSH_TITLE = "小婉";

    /**
     * 主动消息投递入口：逐条落库 + 4 层投递，返回落库 messageId 列表。
     *
     * <p><b>⚠️ 本方法同步阻塞：</b>每条在线 segment 最多阻塞 {@code ackTimeoutMs}（默认 5s）
     * 等待客户端 ACK，多条 segment 累加（n 条最坏阻塞 n × ackTimeoutMs）。
     * 调用方<b>必须在独立线程 / 异步上下文执行</b>，禁止在 {@code @Scheduled} 主循环
     * 或 WS IO 线程直接同步调用，否则会阻塞调度器 / IO 线程，导致调度饥饿或连接处理停滞。
     * Phase J 的 Dispatcher 会用 {@code @Async} 隔离投递。
     *
     * @param userId      目标用户 ID
     * @param characterId 角色 ID（本期备用，落库暂不用）
     * @param segments    已生成好的消息文案列表（调用方拆气泡）
     * @return 落库后每条消息的 ID，顺序与 segments 一致
     */
    public List<Long> deliver(Long userId, Long characterId, List<String> segments) {
        List<Long> messageIds = new ArrayList<>(segments.size());
        for (String segment : segments) {
            // 主动消息投递：标记 is_proactive=true（区别于对话回复 handleAiReply）
            MessageEntity saved = messageService.saveAiMessage(userId, segment, true);
            Long messageId = saved.getId();
            messageIds.add(messageId);
            deliverOne(userId, messageId, saved, segment);
        }
        return messageIds;
    }

    private void deliverOne(Long userId, Long messageId, MessageEntity entity, String segment) {
        Optional<WebSocketSession> sessionOpt = sessionManager.getSession(userId);
        if (sessionOpt.isEmpty()) {
            // 离线 → 直接 L3
            pushOffline(userId, messageId, segment);
            return;
        }
        // L1 在线直推 + L2 注册 ACK future
        pushFrame(sessionOpt.get(), new WsNewMessage(messageService.toData(entity)));
        CompletableFuture<Void> future = new CompletableFuture<>();
        pendingAcks.put(messageId, future);
        try {
            future.orTimeout(ackTimeoutMs, TimeUnit.MILLISECONDS).join();
            log.info("主动消息 ACK 确认送达: userId={}, msgId={}", userId, messageId);
        } catch (Exception e) {
            if (e.getCause() instanceof TimeoutException) {
                log.info("主动消息 ACK 超时，转离线推送: userId={}, msgId={}", userId, messageId);
            } else {
                log.warn("主动消息 ACK 等待异常，转离线推送: userId={}, msgId={}", userId, messageId, e);
            }
            pushOffline(userId, messageId, segment);
        } finally {
            pendingAcks.remove(messageId);
        }
    }

    /**
     * 收到客户端 ACK 帧时由 WebSocket handler（H3）调用，complete 对应的 future。
     *
     * @param messageId 客户端 ACK 帧中的 serverMsgId（{@link com.sanyan.common.ws.WsMessage#ackMsgId}）
     */
    public void confirmAck(Long messageId) {
        if (messageId == null) return;
        CompletableFuture<Void> future = pendingAcks.get(messageId);
        if (future != null) {
            future.complete(null);
        }
    }

    private void pushOffline(Long userId, Long messageId, String segment) {
        try {
            PushResult result = pushApi.pushToUser(userId, new PushPayload(PUSH_TITLE, segment, messageId));
            // spec §7.1：推送失败（抛异常 / PushResult.status=FAILED）记 ERROR 日志。
            // 正常返回但 status=FAILED 也是失败，需显式检查，否则静默丢失失败信息。
            if (result != null && result.isFailed()) {
                log.error("离线推送失败（不影响流程，靠 sync 兜底）: userId={}, msgId={}, detail={}",
                        userId, messageId, result.detail());
            }
        } catch (Exception e) {
            // 投递层失败不回滚已生成内容（已落 message），靠 L4 sync 兜底（spec §5.1）
            log.error("离线推送失败（不影响流程，靠 sync 兜底）: userId={}, msgId={}", userId, messageId, e);
        }
    }

    private void pushFrame(WebSocketSession session, Object payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            log.error("主动消息 WS 推送失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 测试可见：判断某 messageId 是否已注册等待 ACK 的 future。
     * 供 {@link DeliveryServiceTest} 在多线程 ACK 用例里轮询等待 future 注册完成。
     */
    boolean hasPendingAck(Long messageId) {
        return pendingAcks.containsKey(messageId);
    }
}
