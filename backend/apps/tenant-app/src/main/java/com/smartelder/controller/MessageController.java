package com.smartelder.controller;

import com.smartelder.common.Result;
import com.smartelder.entity.MessageRecord;
import com.smartelder.security.CurrentUserHolder;
import com.smartelder.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "消息中心", description = "消息记录与已读（MSG-01~MSG-05）")
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @Operation(summary = "我的消息列表")
    @GetMapping
    public Result<List<MessageRecord>> list(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(messageService.listByReceiver(CurrentUserHolder.require().userId(), Math.min(limit, 200)));
    }

    @Operation(summary = "标记已读（MSG-05）")
    @PostMapping("/{messageId}/read")
    public Result<Void> read(@PathVariable Long messageId) {
        messageService.markRead(messageId, CurrentUserHolder.require().userId());
        return Result.ok();
    }
}
