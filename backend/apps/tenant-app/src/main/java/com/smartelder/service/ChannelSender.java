package com.smartelder.service;

/**
 * 消息渠道发送扩展点。一期由 {@link LogChannelSender} 提供实现，
 * 接入微信订阅消息、短信网关时新增实现类即可（需求 6.2 外部系统接口）。
 */
public interface ChannelSender {

    boolean supports(String channel);

    void send(Long receiverId, String title, String content);
}
