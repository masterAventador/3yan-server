/**
 * sanyan-push-api：Push 域对内契约（java-backend §2.2）。
 *
 * <p>含 {@link com.sanyan.push.PushApi} 接口（注册设备 token + 给用户推送）
 * + {@link com.sanyan.push.PushChannel} 接口（单一推送通道抽象）
 * + {@link com.sanyan.push.dto.PushPayload} / {@link com.sanyan.push.dto.DeviceTokenDto}
 * / {@link com.sanyan.push.dto.PushResult} record。零依赖。
 *
 * <p>实现在 sanyan-push-core，通过 {@code PushApiImpl} 暴露（Phase G 创建）。
 * 本期 L3 实推为占位，status 返回 PENDING。
 */
package com.sanyan.push;
