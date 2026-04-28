package com.sky.controller.admin;

import com.sky.dto.AiCustomerServiceChatDTO;
import com.sky.result.Result;
import com.sky.service.AiCustomerService;
import com.sky.vo.AiCustomerServiceChatVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/ai/customer-service")
@Slf4j
public class AiCustomerServiceController {

    @Autowired
    private AiCustomerService aiCustomerService;

    @PostMapping("/chat")
    public Result<AiCustomerServiceChatVO> chat(@RequestBody AiCustomerServiceChatDTO chatDTO) {
        log.info("智能客服收到消息：{}", chatDTO.getMessage());
        return Result.success(aiCustomerService.chat(chatDTO));
    }
}
