package com.sky.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "智能客服聊天响应")
public class AiCustomerServiceChatVO implements Serializable {

    @ApiModelProperty("模型回复")
    private String answer;

    @ApiModelProperty("是否使用了数据库")
    private Boolean usedDatabase;

    @ApiModelProperty("执行日志")
    private List<Map<String, Object>> executionLogs;
}
