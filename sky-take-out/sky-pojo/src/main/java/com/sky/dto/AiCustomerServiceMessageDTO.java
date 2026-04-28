package com.sky.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel(description = "智能客服单条消息")
public class AiCustomerServiceMessageDTO implements Serializable {

    @ApiModelProperty("角色：user/assistant")
    private String role;

    @ApiModelProperty("消息内容")
    private String content;
}
