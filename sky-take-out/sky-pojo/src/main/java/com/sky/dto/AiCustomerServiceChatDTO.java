package com.sky.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@ApiModel(description = "智能客服聊天请求")
public class AiCustomerServiceChatDTO implements Serializable {

    @ApiModelProperty("当前用户消息")
    private String message;

    @ApiModelProperty("历史消息")
    private List<AiCustomerServiceMessageDTO> history;

    @ApiModelProperty("是否允许数据库写操作")
    private Boolean allowWrite = true;
}
