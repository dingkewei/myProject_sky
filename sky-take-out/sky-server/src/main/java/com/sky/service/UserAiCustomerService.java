package com.sky.service;

import com.sky.dto.AiCustomerServiceChatDTO;
import com.sky.vo.AiCustomerServiceChatVO;

public interface UserAiCustomerService {

    AiCustomerServiceChatVO chat(AiCustomerServiceChatDTO chatDTO);
}
