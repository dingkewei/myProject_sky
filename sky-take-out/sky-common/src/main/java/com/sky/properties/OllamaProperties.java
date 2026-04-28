package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.ollama")
@Data
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";

    private String model = "deepseek-r1:7b";

    private Integer maxToolCalls = 3;
}
