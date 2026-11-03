package com.smartelder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogChannelSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(LogChannelSender.class);

    @Override
    public boolean supports(String channel) {
        return true;
    }

    @Override
    public void send(Long receiverId, String title, String content) {
        log.info("[模拟触达] receiver={} title={} content={}", receiverId, title, content);
    }
}
