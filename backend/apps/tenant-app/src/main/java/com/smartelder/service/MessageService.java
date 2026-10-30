package com.smartelder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartelder.entity.MessageRecord;
import com.smartelder.mapper.MessageRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 消息触达（需求 MSG-01~05）。一期实现站内消息入库 + 日志输出，
 * 微信订阅消息/短信通过 {@link ChannelSender} 扩展点接入。
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final MessageRecordMapper messageMapper;
    private final List<ChannelSender> senders;

    public MessageService(MessageRecordMapper messageMapper, List<ChannelSender> senders) {
        this.messageMapper = messageMapper;
        this.senders = senders;
    }

    /**
     * @param type     ALERT / SERVICE / SYSTEM
     * @param channel  IN_APP / SMS / WECHAT
     */
    public void send(Long receiverId, String type, String channel, String title, String content, Long bizId) {
        MessageRecord record = new MessageRecord();
        record.setReceiverId(receiverId);
        record.setType(type);
        record.setChannel(channel);
        record.setTitle(title);
        record.setContent(content);
        record.setBizId(bizId);
        record.setStatus("SENT");
        record.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(record);

        senders.stream()
                .filter(s -> s.supports(channel))
                .forEach(s -> {
                    try {
                        s.send(receiverId, title, content);
                    } catch (RuntimeException e) {
                        log.warn("渠道 {} 发送失败，receiver={}：{}", channel, receiverId, e.getMessage());
                    }
                });
    }

    public List<MessageRecord> listByReceiver(Long receiverId, int limit) {
        return messageMapper.selectList(Wrappers.<MessageRecord>lambdaQuery()
                .eq(MessageRecord::getReceiverId, receiverId)
                .orderByDesc(MessageRecord::getCreatedAt)
                .last("LIMIT " + limit));
    }

    public void markRead(Long messageId, Long receiverId) {
        MessageRecord record = messageMapper.selectById(messageId);
        if (record == null || !record.getReceiverId().equals(receiverId)) {
            return;
        }
        record.setStatus("READ");
        record.setReadAt(LocalDateTime.now());
        messageMapper.updateById(record);
    }
}
