package com.sky.controller.user;

import com.sky.dto.AiCustomerServiceChatDTO;
import com.sky.result.Result;
import com.sky.service.UserAiCustomerService;
import com.sky.vo.AiCustomerServiceChatVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("userAiCustomerServiceController")
@RequestMapping("/user/ai/customer-service")
@Slf4j
public class AiCustomerServiceController {

    @Autowired
    private UserAiCustomerService userAiCustomerService;

    @PostMapping("/chat")
    public Result<AiCustomerServiceChatVO> chat(@RequestBody AiCustomerServiceChatDTO chatDTO) {
        log.info("用户智能客服收到消息：{}", chatDTO.getMessage());
        return Result.success(userAiCustomerService.chat(chatDTO));
    }
}
