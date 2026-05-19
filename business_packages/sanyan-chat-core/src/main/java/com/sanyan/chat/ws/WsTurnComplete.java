package com.sanyan.chat.ws;

import com.sanyan.common.ws.WsEventType;
import lombok.Data;

/**
 * AI 一轮回复结束信号。{@link ChatWebSocketHandler} 处理 {@code send_message} 时，
 * 把豆包/DeepSeek 返回的完整文本拆成 N 个气泡按节奏推完后，发本事件标记"这一轮结束"。
 *
 * <p>异常路径（AI 调用失败、推送失败等）也会发本事件——确保客户端/dogfood
 * 不会因为没收到结束信号而无限等待。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code clientMsgId}：关联回触发本轮的 user 消息（客户端用它把"打字状态"绑回对应输入框）</li>
 *   <li>{@code messageCount}：本轮共推送了多少个气泡（监控用，方便统计平均拆分度）</li>
 * </ul>
 */
@Data
public class WsTurnComplete {
    private final String type = WsEventType.TURN_COMPLETE;
    private String clientMsgId;
    private int messageCount;

    public WsTurnComplete(String clientMsgId, int messageCount) {
        this.clientMsgId = clientMsgId;
        this.messageCount = messageCount;
    }
}
