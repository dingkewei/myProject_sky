package com.sky.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.dto.AiCustomerServiceChatDTO;
import com.sky.dto.AiCustomerServiceMessageDTO;
import com.sky.properties.OllamaProperties;
import com.sky.service.AiCustomerService;
import com.sky.vo.AiCustomerServiceChatVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AiCustomerServiceImpl implements AiCustomerService {

    private static final Pattern THINK_PATTERN = Pattern.compile("(?s)<think>.*?</think>");
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("(?s)```json\\s*(\\{.*?})\\s*```");
    private static final Pattern RAW_JSON_PATTERN = Pattern.compile("(?s)(\\{\\s*\"type\"\\s*:\\s*\"(?:tool|response|message)\".*})");
    private static final Pattern FENCE_PATTERN = Pattern.compile("(?s)^```[a-zA-Z]*\\s*|\\s*```$");
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("(?i)(?:from|join|update|into|delete\\s+from)\\s+`?(?:[a-zA-Z0-9_]+`?\\.)?`?([a-zA-Z0-9_]+)`?");
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)\\blimit\\s+(\\d+)(?:\\s*,\\s*(\\d+))?\\b");
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("(?i)\\border\\s+by\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern EMPLOYEE_NAME_PATTERN = Pattern.compile("(?:姓名|名为|名字|叫)\\s*[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,20})");
    private static final Pattern EMPLOYEE_USERNAME_PATTERN = Pattern.compile("(?:账号|账户|用户名)\\s*[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,32})");
    private static final Pattern EMPLOYEE_ID_NUMBER_PATTERN = Pattern.compile("(?:身份证|证件号|id_number)\\s*[:：]?\\s*([0-9Xx]{6,18})");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("1\\d{10}");
    private static final int MAX_PREVIEW_ROWS = 20;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OllamaProperties ollamaProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AiCustomerServiceChatVO chat(AiCustomerServiceChatDTO chatDTO) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long chatStartTime = System.currentTimeMillis();
        List<Map<String, Object>> executionLogs = new ArrayList<>();
        List<ConversationMessage> conversation = buildConversation(chatDTO);
        String latestUserMessage = getLatestUserMessage(conversation);
        SchemaContext schemaContext = buildSchemaContext();
        boolean allowWrite = Boolean.TRUE.equals(chatDTO.getAllowWrite());
        int maxToolCalls = ollamaProperties.getMaxToolCalls() == null ? 3 : ollamaProperties.getMaxToolCalls();
        boolean usedDatabase = false;

        log.info("[AI-CS:{}] chat start | model={} | allowWrite={} | historySize={} | maxToolCalls={}",
                traceId, ollamaProperties.getModel(), allowWrite, conversation.size(), maxToolCalls);
        logBlock(traceId, "latest user message", latestUserMessage);
        logBlock(traceId, "schema snapshot", schemaContext.getSnapshot());

        for (int i = 0; i < maxToolCalls; i++) {
            int round = i + 1;
            long roundStartTime = System.currentTimeMillis();
            String prompt = buildPrompt(conversation, schemaContext.getSnapshot(), allowWrite, false);
            logBlock(traceId, "tool round " + round + " prompt", prompt);
            String modelOutput = callOllama(prompt);
            logBlock(traceId, "tool round " + round + " model output", modelOutput);
            String sanitizedOutput = sanitizeModelOutput(modelOutput);
            logBlock(traceId, "tool round " + round + " sanitized output", sanitizedOutput);
            String normalizedAssistantAnswer = normalizeAssistantAnswer(sanitizedOutput);
            ToolRequest parsedToolRequest = parseToolRequest(sanitizedOutput);
            ToolRequest inferredToolRequest = inferToolRequestFromMessage(latestUserMessage, allowWrite);
            ToolRequest toolRequest = parsedToolRequest;
            boolean toolRequestInferred = false;
            if (shouldPreferInferredToolRequest(parsedToolRequest, inferredToolRequest)) {
                toolRequest = inferredToolRequest;
                toolRequestInferred = true;
                log.info("[AI-CS:{}] tool round {} inferred tool request from user intent: {}",
                        traceId, round, toJson(toolRequest));
            }
            log.info("[AI-CS:{}] tool round {} parsed tool request: {}",
                    traceId, round, parsedToolRequest == null ? "<none>" : toJson(parsedToolRequest));

            if (toolRequest == null) {
                List<Map<String, Object>> normalizedExecutionLogs = deduplicateExecutionLogs(executionLogs);
                logBlock(traceId, "direct answer", normalizedAssistantAnswer);
                logStructured(traceId, "execution logs", normalizedExecutionLogs);
                log.info("[AI-CS:{}] chat end without tool call | usedDatabase={} | executionLogCount={} | totalCostMs={}",
                        traceId, usedDatabase, normalizedExecutionLogs.size(), System.currentTimeMillis() - chatStartTime);
                return AiCustomerServiceChatVO.builder()
                        .answer(normalizedAssistantAnswer)
                        .usedDatabase(usedDatabase)
                        .executionLogs(normalizedExecutionLogs)
                        .build();
            }

            usedDatabase = true;
            logBlock(traceId, "tool round " + round + " raw sql", toolRequest.getSql());
            String sql = normalizeSql(toolRequest.getSql());
            logBlock(traceId, "tool round " + round + " normalized sql", sql);
            String adjustedSql = applyRecencyOrdering(sql, latestUserMessage);
            if (!adjustedSql.equals(sql)) {
                logBlock(traceId, "tool round " + round + " adjusted sql", adjustedSql);
            }
            sql = adjustedSql;

            Map<String, Object> toolResult;
            boolean success = false;
            try {
                validateSql(sql, allowWrite, schemaContext.getTableNames());
                log.info("[AI-CS:{}] tool round {} SQL validation passed", traceId, round);
                toolResult = executeSql(sql);
                success = Boolean.TRUE.equals(toolResult.get("success"));
            } catch (IllegalArgumentException | DataAccessException ex) {
                log.warn("[AI-CS:{}] tool round {} SQL execution failed: {}", traceId, round, ex.getMessage());
                toolResult = buildErrorResult(sql, ex.getMessage());
            }

            executionLogs.add(buildLogItem(toolRequest, sql, success, toolResult));
            logStructured(traceId, "tool round " + round + " execution result", toolResult);
            conversation.add(new ConversationMessage("assistant", toolRequestInferred ? toJson(toolRequest) : sanitizedOutput));
            conversation.add(new ConversationMessage("tool", "Database execution result: " + toJson(toolResult)));

            boolean stopLoop = success || shouldStopToolLoop(executionLogs, sql) || shouldStopOnError(toolResult);
            log.info("[AI-CS:{}] tool round {} finished | success={} | stopLoop={} | costMs={}",
                    traceId, round, success, stopLoop, System.currentTimeMillis() - roundStartTime);
            if (stopLoop) {
                break;
            }
        }

        String finalPrompt = buildPrompt(conversation, schemaContext.getSnapshot(), allowWrite, true);
        logBlock(traceId, "final prompt", finalPrompt);
        String finalModelOutput = callOllama(finalPrompt);
        logBlock(traceId, "final model output", finalModelOutput);
        String finalAnswer = normalizeAssistantAnswer(sanitizeModelOutput(finalModelOutput));
        logBlock(traceId, "final sanitized answer", finalAnswer);
        List<Map<String, Object>> normalizedExecutionLogs = deduplicateExecutionLogs(executionLogs);
        if (!CollectionUtils.isEmpty(normalizedExecutionLogs)) {
            log.info("[AI-CS:{}] final answer replaced by structured execution summary", traceId);
            finalAnswer = buildFallbackAnswer(normalizedExecutionLogs);
        } else if (parseToolRequest(finalAnswer) != null || !StringUtils.hasText(finalAnswer) || shouldUseFallbackAnswer(finalAnswer)) {
            log.info("[AI-CS:{}] final answer replaced by fallback", traceId);
            finalAnswer = buildFallbackAnswer(normalizedExecutionLogs);
        }
        logBlock(traceId, "final answer", finalAnswer);
        logStructured(traceId, "execution logs", normalizedExecutionLogs);
        log.info("[AI-CS:{}] chat end | usedDatabase={} | executionLogCount={} | totalCostMs={}",
                traceId, usedDatabase, normalizedExecutionLogs.size(), System.currentTimeMillis() - chatStartTime);

        return AiCustomerServiceChatVO.builder()
                .answer(finalAnswer)
                .usedDatabase(usedDatabase)
                .executionLogs(normalizedExecutionLogs)
                .build();
    }

    private List<ConversationMessage> buildConversation(AiCustomerServiceChatDTO chatDTO) {
        List<ConversationMessage> messages = new ArrayList<>();
        if (!CollectionUtils.isEmpty(chatDTO.getHistory())) {
            for (AiCustomerServiceMessageDTO item : chatDTO.getHistory()) {
                if (!StringUtils.hasText(item.getContent())) {
                    continue;
                }
                String role = StringUtils.hasText(item.getRole()) ? item.getRole().trim().toLowerCase(Locale.ROOT) : "user";
                String normalizedContent = normalizeConversationContent(role, item.getContent().trim());
                messages.add(new ConversationMessage(role, normalizedContent));
            }
        }
        if (StringUtils.hasText(chatDTO.getMessage())) {
            messages.add(new ConversationMessage("user", chatDTO.getMessage().trim()));
        }
        return messages;
    }

    private String normalizeConversationContent(String role, String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String trimmedContent = content.trim();
        if (!"assistant".equalsIgnoreCase(role)) {
            return trimmedContent;
        }
        ToolRequest toolRequest = parseToolRequest(trimmedContent);
        if (toolRequest != null) {
            if (StringUtils.hasText(toolRequest.getReason())) {
                return "上一轮助手生成的数据库操作请求：" + toolRequest.getReason();
            }
            return "上一轮助手生成了一条数据库操作请求。";
        }
        String normalizedAnswer = normalizeAssistantAnswer(trimmedContent);
        return StringUtils.hasText(normalizedAnswer) ? normalizedAnswer : trimmedContent;
    }

    private String getLatestUserMessage(List<ConversationMessage> conversation) {
        if (CollectionUtils.isEmpty(conversation)) {
            return "";
        }
        for (int i = conversation.size() - 1; i >= 0; i--) {
            ConversationMessage message = conversation.get(i);
            if ("user".equalsIgnoreCase(message.getRole()) && StringUtils.hasText(message.getContent())) {
                return message.getContent().trim();
            }
        }
        return "";
    }

    private String buildPrompt(List<ConversationMessage> conversation, String schemaSnapshot, boolean allowWrite, boolean finalAnswerOnly) {
        StringBuilder builder = new StringBuilder();
        builder.append(buildSystemPrompt(schemaSnapshot, allowWrite, finalAnswerOnly));
        builder.append("Conversation:\n");
        for (ConversationMessage message : conversation) {
            builder.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
        builder.append("assistant:");
        return builder.toString();
    }

    private void logBlock(String traceId, String title, String content) {
        if (!StringUtils.hasText(content)) {
            log.info("[AI-CS:{}] {}: <empty>", traceId, title);
            return;
        }
        log.info("[AI-CS:{}] {}:\n{}", traceId, title, content);
    }

    private void logStructured(String traceId, String title, Object value) {
        logBlock(traceId, title, toJson(value));
    }

    private String buildSystemPrompt(String schemaSnapshot, boolean allowWrite, boolean finalAnswerOnly) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are the AI customer service assistant for 小威外卖.\n")
                .append("Always answer the final user-facing response in Chinese.\n")
                .append("Never call the product by an English name.\n")
                .append("You can do normal conversation, read database data, and execute controlled CRUD operations.\n")
                .append("Current database write permission: ").append(allowWrite ? "ENABLED" : "DISABLED").append(".\n")
                .append("If a database action is required, return only one JSON object with no markdown and no extra explanation.\n")
                .append("The JSON format must be: ")
                .append("{\"type\":\"tool\",\"tool\":\"database\",\"sql\":\"single MySQL statement\",\"reason\":\"short reason\"}\n")
                .append("Rules:\n")
                .append("1. Only one SQL statement is allowed.\n")
                .append("2. Only SELECT, INSERT, UPDATE, DELETE are allowed.\n")
                .append("3. Never use DROP, TRUNCATE, ALTER, CREATE, comments, or multiple statements.\n")
                .append("4. Never invent tables or columns outside the schema summary.\n")
                .append("5. For record listing queries, prefer LIMIT 20.\n")
                .append("6. UPDATE and DELETE statements must include WHERE conditions.\n")
                .append("7. If write permission is disabled, never propose INSERT, UPDATE, or DELETE.\n")
                .append("8. If a tool result contains success=false or an error message, correct the SQL and try again if possible.\n")
                .append("9. For query results, summarize the concrete rows and key fields directly in Chinese.\n")
                .append("10. For write results, explain whether the operation succeeded and why if it failed.\n")
                .append("11. Never ask the user to inspect logs, JSON, or SQL by themselves.\n")
                .append("12. Never expose passwords or password hashes.\n")
                .append("13. When the user asks for latest or recent records, add a descending order by the most relevant time or id column. For orders, prefer ORDER BY order_time DESC before LIMIT.\n")
                .append("14. Read-only queries and statistics are allowed even when write permission is disabled.\n")
                .append("15. Sales ranking queries should use order_detail joined with orders, aggregate SUM(number), and sort the result in descending order.\n")
                .append("Database schema summary:\n")
                .append(schemaSnapshot)
                .append("\n");
        if (finalAnswerOnly) {
            builder.append("Do not call tools again. Provide the final answer in Chinese based only on the conversation and tool results.\n");
        } else {
            builder.append("If database access is not required, reply directly in Chinese.\n");
        }
        return builder.toString();
    }

    private String callOllama(String prompt) {
        String url = ollamaProperties.getBaseUrl() + "/api/generate";
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", ollamaProperties.getModel());
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0.2);
        requestBody.put("options", options);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        String responseBody = restTemplate.postForObject(url, requestEntity, String.class);
        if (!StringUtils.hasText(responseBody)) {
            return "模型没有返回有效内容，请确认 Ollama 服务是否已启动。";
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode responseNode = jsonNode.get("response");
            return responseNode == null ? "" : responseNode.asText("");
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Ollama response: {}", responseBody, e);
            return "模型响应解析失败，请检查 Ollama 服务配置。";
        }
    }

    private String sanitizeModelOutput(String modelOutput) {
        if (!StringUtils.hasText(modelOutput)) {
            return "模型没有返回可用内容。";
        }
        String sanitized = THINK_PATTERN.matcher(modelOutput).replaceAll("").trim();
        return FENCE_PATTERN.matcher(sanitized).replaceAll("").trim();
    }

    private String normalizeAssistantAnswer(String output) {
        String directResponseContent = extractDirectResponseContent(output);
        if (StringUtils.hasText(directResponseContent)) {
            return directResponseContent;
        }
        String toolReason = extractToolReason(output);
        return StringUtils.hasText(toolReason) ? toolReason : output;
    }

    private String extractDirectResponseContent(String output) {
        String json = extractJsonPayload(output);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String type = root.path("type").asText("");
            if (("response".equalsIgnoreCase(type) || "message".equalsIgnoreCase(type))
                    && root.has("content")) {
                return root.path("content").asText("");
            }
        } catch (JsonProcessingException ignored) {
            return null;
        }
        return null;
    }

    private String extractToolReason(String output) {
        String json = extractJsonPayload(output);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String type = root.path("type").asText("");
            if (!"tool".equalsIgnoreCase(type)) {
                return null;
            }
            String reason = root.path("reason").asText("");
            if (StringUtils.hasText(reason)) {
                return reason;
            }
            String sql = root.path("sql").asText("");
            if (StringUtils.hasText(sql)) {
                return "已生成数据库操作请求。";
            }
        } catch (JsonProcessingException ignored) {
            return null;
        }
        return null;
    }

    private String extractJsonPayload(String output) {
        if (!StringUtils.hasText(output)) {
            return null;
        }

        Matcher jsonBlockMatcher = JSON_BLOCK_PATTERN.matcher(output);
        if (jsonBlockMatcher.find()) {
            return jsonBlockMatcher.group(1);
        }

        Matcher rawMatcher = RAW_JSON_PATTERN.matcher(output);
        if (rawMatcher.find()) {
            return rawMatcher.group(1);
        }

        if (output.startsWith("{") && output.endsWith("}")) {
            return output;
        }
        return null;
    }

    private ToolRequest parseToolRequest(String output) {
        String json = extractJsonPayload(output);
        if (!StringUtils.hasText(json)) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            String type = root.path("type").asText("");
            String tool = root.path("tool").asText("");
            if (!"tool".equalsIgnoreCase(type)) {
                return null;
            }
            String sql = root.path("sql").asText("");
            if (!StringUtils.hasText(sql)) {
                return null;
            }
            ToolRequest toolRequest = new ToolRequest();
            toolRequest.setType("tool");
            toolRequest.setTool(StringUtils.hasText(tool) ? tool : "database");
            toolRequest.setSql(sql);
            String reason = root.path("reason").asText("");
            if (!StringUtils.hasText(reason) && root.has("content")) {
                reason = root.path("content").asText("");
            }
            toolRequest.setReason(reason);
            return toolRequest;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool JSON: {}", output, e);
            return null;
        }
    }

    private ToolRequest inferToolRequestFromMessage(String latestUserMessage, boolean allowWrite) {
        if (!StringUtils.hasText(latestUserMessage)) {
            return null;
        }

        String normalizedMessage = latestUserMessage.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (isTodayOrderCountIntent(normalizedMessage)) {
            return buildToolRequest(buildTodayOrderCountSql(), "统计今天的订单数量");
        }
        if (isTodayOrderListIntent(normalizedMessage)) {
            return buildToolRequest(buildTodayOrderListSql(resolveRequestedLimit(latestUserMessage, 20)),
                    "查询今天的订单列表");
        }
        if (isRecentOrderListIntent(normalizedMessage)) {
            return buildToolRequest(buildRecentOrderListSql(resolveRequestedLimit(latestUserMessage, 10)),
                    "查询最近的订单列表");
        }
        if (allowWrite && isEmployeeCreateIntent(normalizedMessage)) {
            return buildToolRequest(buildEmployeeInsertSql(latestUserMessage), "新增员工");
        }
        if (isEmployeeListIntent(normalizedMessage)) {
            return buildToolRequest(
                    "SELECT id, name, username, phone, sex, status, create_time FROM employee ORDER BY id ASC LIMIT 20",
                    "查询员工列表");
        }
        if (isTopSalesIntent(normalizedMessage)) {
            return buildToolRequest(buildTopSalesSql(resolveRequestedLimit(latestUserMessage, 3)),
                    "统计销量最高的菜品或套餐");
        }
        if (isDishListIntent(normalizedMessage)) {
            return buildToolRequest(
                    "SELECT d.id, d.name, c.name AS category_name, d.price, d.status, d.update_time " +
                            "FROM dish d LEFT JOIN category c ON d.category_id = c.id " +
                            "ORDER BY d.update_time DESC LIMIT 20",
                    "查看菜品列表");
        }
        if (isSetmealListIntent(normalizedMessage)) {
            return buildToolRequest(
                    "SELECT s.id, s.name, c.name AS category_name, s.price, s.status, s.update_time " +
                            "FROM setmeal s LEFT JOIN category c ON s.category_id = c.id " +
                            "ORDER BY s.update_time DESC LIMIT 20",
                    "查看套餐列表");
        }
        return null;
    }

    private boolean shouldPreferInferredToolRequest(ToolRequest parsedToolRequest, ToolRequest inferredToolRequest) {
        return inferredToolRequest != null;
    }

    private boolean isEmployeeCreateIntent(String normalizedMessage) {
        return normalizedMessage.contains("员工")
                && (normalizedMessage.contains("新增")
                || normalizedMessage.contains("添加")
                || normalizedMessage.contains("加入")
                || normalizedMessage.contains("创建"));
    }

    private boolean isEmployeeListIntent(String normalizedMessage) {
        return normalizedMessage.contains("员工")
                && (normalizedMessage.contains("列表")
                || normalizedMessage.contains("查询")
                || normalizedMessage.contains("查看")
                || normalizedMessage.contains("列出"));
    }

    private boolean isTodayOrderCountIntent(String normalizedMessage) {
        return containsTodayOrderIntent(normalizedMessage) && containsOrderCountKeyword(normalizedMessage);
    }

    private boolean isTodayOrderListIntent(String normalizedMessage) {
        return containsTodayOrderIntent(normalizedMessage)
                && !containsOrderCountKeyword(normalizedMessage)
                && (normalizedMessage.contains("查询")
                || normalizedMessage.contains("查看")
                || normalizedMessage.contains("列出")
                || normalizedMessage.contains("列表")
                || normalizedMessage.contains("明细")
                || normalizedMessage.contains("详情"));
    }

    private boolean isRecentOrderListIntent(String normalizedMessage) {
        return normalizedMessage.contains("订单")
                && !containsOrderCountKeyword(normalizedMessage)
                && (normalizedMessage.contains("最近")
                || normalizedMessage.contains("最新")
                || normalizedMessage.contains("刚刚"));
    }

    private boolean containsTodayOrderIntent(String normalizedMessage) {
        return normalizedMessage.contains("订单")
                && (normalizedMessage.contains("今天")
                || normalizedMessage.contains("今日")
                || normalizedMessage.contains("当天"));
    }

    private boolean containsOrderCountKeyword(String normalizedMessage) {
        return normalizedMessage.contains("数量")
                || normalizedMessage.contains("多少")
                || normalizedMessage.contains("几单")
                || normalizedMessage.contains("订单数")
                || normalizedMessage.contains("订单量")
                || normalizedMessage.contains("count");
    }

    private boolean isTopSalesIntent(String normalizedMessage) {
        boolean salesIntent = normalizedMessage.contains("销量")
                || normalizedMessage.contains("热销")
                || normalizedMessage.contains("卖得最好")
                || normalizedMessage.contains("销量最高")
                || normalizedMessage.contains("top");
        boolean targetIntent = normalizedMessage.contains("菜品")
                || normalizedMessage.contains("套餐")
                || normalizedMessage.contains("dish")
                || normalizedMessage.contains("setmeal");
        return salesIntent && targetIntent;
    }

    private boolean isDishListIntent(String normalizedMessage) {
        return !normalizedMessage.contains("销量")
                && !normalizedMessage.contains("热销")
                && (normalizedMessage.contains("列出菜品")
                || normalizedMessage.contains("查看菜品")
                || normalizedMessage.contains("菜品列表")
                || normalizedMessage.contains("我的菜品"));
    }

    private boolean isSetmealListIntent(String normalizedMessage) {
        return !normalizedMessage.contains("销量")
                && !normalizedMessage.contains("热销")
                && (normalizedMessage.contains("列出套餐")
                || normalizedMessage.contains("查看套餐")
                || normalizedMessage.contains("套餐列表")
                || normalizedMessage.contains("我的套餐"));
    }

    private int resolveRequestedLimit(String message, int defaultLimit) {
        if (!StringUtils.hasText(message)) {
            return defaultLimit;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(message);
        if (matcher.find()) {
            try {
                return Math.max(1, Math.min(20, Integer.parseInt(matcher.group(1))));
            } catch (NumberFormatException ignored) {
                return defaultLimit;
            }
        }
        if (message.contains("三")) {
            return 3;
        }
        if (message.contains("五")) {
            return 5;
        }
        if (message.contains("十")) {
            return 10;
        }
        return defaultLimit;
    }

    private String buildTopSalesSql(int limit) {
        return "SELECT CASE WHEN od.dish_id IS NOT NULL THEN 'dish' ELSE 'setmeal' END AS item_type, " +
                "od.name AS item_name, SUM(od.number) AS sales_count " +
                "FROM order_detail od INNER JOIN orders o ON od.order_id = o.id " +
                "WHERE o.status = 5 " +
                "GROUP BY CASE WHEN od.dish_id IS NOT NULL THEN 'dish' ELSE 'setmeal' END, od.name " +
                "ORDER BY sales_count DESC, item_name ASC LIMIT " + limit;
    }

    private String buildTodayOrderCountSql() {
        return "SELECT COUNT(*) AS order_count FROM orders " +
                "WHERE order_time >= CURDATE() AND order_time < DATE_ADD(CURDATE(), INTERVAL 1 DAY)";
    }

    private String buildTodayOrderListSql(int limit) {
        return "SELECT id, number, order_time, amount, status, phone, consignee, address " +
                "FROM orders " +
                "WHERE order_time >= CURDATE() AND order_time < DATE_ADD(CURDATE(), INTERVAL 1 DAY) " +
                "ORDER BY order_time DESC LIMIT " + limit;
    }

    private String buildRecentOrderListSql(int limit) {
        return "SELECT id, number, order_time, amount, status, phone, consignee, address " +
                "FROM orders ORDER BY order_time DESC LIMIT " + limit;
    }

    private ToolRequest buildToolRequest(String sql, String reason) {
        ToolRequest toolRequest = new ToolRequest();
        toolRequest.setType("tool");
        toolRequest.setTool("database");
        toolRequest.setSql(sql);
        toolRequest.setReason(reason);
        return toolRequest;
    }

    private String buildEmployeeInsertSql(String latestUserMessage) {
        long seed = System.currentTimeMillis();
        String name = defaultIfBlank(extractFirstMatch(latestUserMessage, EMPLOYEE_NAME_PATTERN), "新员工");
        String username = extractFirstMatch(latestUserMessage, EMPLOYEE_USERNAME_PATTERN);
        if (!StringUtils.hasText(username)) {
            username = "xw" + String.format("%06d", seed % 1_000_000);
        }
        String phone = extractFirstMatch(latestUserMessage, MOBILE_PATTERN);
        if (!StringUtils.hasText(phone)) {
            phone = "13" + String.format("%09d", seed % 1_000_000_000L);
        }
        String normalizedMessage = latestUserMessage.replaceAll("\\s+", "");
        String sex = normalizedMessage.contains("女") ? "2" : "1";
        String status = normalizedMessage.contains("禁用") ? "0" : "1";
        String idNumber = extractFirstMatch(latestUserMessage, EMPLOYEE_ID_NUMBER_PATTERN);
        if (!StringUtils.hasText(idNumber)) {
            idNumber = "11010119900101" + String.format("%04d", seed % 10_000);
        }
        String password = "e10adc3949ba59abbe56e057f20f883e";
        return "INSERT INTO employee (name, username, password, phone, sex, id_number, status) VALUES ('"
                + escapeSqlLiteral(name) + "', '"
                + escapeSqlLiteral(username) + "', '"
                + password + "', '"
                + escapeSqlLiteral(phone) + "', '"
                + sex + "', '"
                + escapeSqlLiteral(idNumber) + "', "
                + status + ")";
    }

    private String extractFirstMatch(String text, Pattern pattern) {
        if (!StringUtils.hasText(text) || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''").trim();
    }

    private String normalizeSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("模型没有生成可执行的 SQL。");
        }
        String normalized = sql.trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String applyRecencyOrdering(String sql, String userMessage) {
        if (!shouldPreferRecentOrdering(userMessage)) {
            return sql;
        }

        String lowerSql = sql.toLowerCase(Locale.ROOT);
        Matcher misplacedLimitMatcher = LIMIT_PATTERN.matcher(sql);
        Matcher orderMatcher = ORDER_BY_PATTERN.matcher(sql);
        if (misplacedLimitMatcher.find() && orderMatcher.find() && orderMatcher.start() > misplacedLimitMatcher.start()) {
            String beforeLimit = sql.substring(0, misplacedLimitMatcher.start()).trim();
            String limitClause = sql.substring(misplacedLimitMatcher.start(), orderMatcher.start()).trim();
            String orderClause = sql.substring(orderMatcher.start()).trim();
            sql = beforeLimit + " " + orderClause + " " + limitClause;
            lowerSql = sql.toLowerCase(Locale.ROOT);
        }

        if (!lowerSql.startsWith("select") || ORDER_BY_PATTERN.matcher(sql).find()) {
            return sql;
        }
        if (lowerSql.contains(" group by ")
                || lowerSql.contains(" count(")
                || lowerSql.contains(" sum(")
                || lowerSql.contains(" avg(")
                || lowerSql.contains(" min(")
                || lowerSql.contains(" max(")) {
            return sql;
        }
        if (!"orders".equals(extractPrimaryTable(sql))) {
            return sql;
        }

        Matcher limitMatcher = LIMIT_PATTERN.matcher(sql);
        String orderClause = " ORDER BY order_time DESC, id DESC";
        if (limitMatcher.find()) {
            String beforeLimit = sql.substring(0, limitMatcher.start()).trim();
            String limitClause = sql.substring(limitMatcher.start()).trim();
            return beforeLimit + orderClause + " " + limitClause;
        }
        return sql + orderClause;
    }

    private boolean shouldPreferRecentOrdering(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return false;
        }
        String normalizedMessage = userMessage.toLowerCase(Locale.ROOT);
        return normalizedMessage.contains("最近")
                || normalizedMessage.contains("最新")
                || normalizedMessage.contains("recent")
                || normalizedMessage.contains("latest");
    }

    private void validateSql(String sql, boolean allowWrite, Set<String> allowedTableNames) {
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        if (lowerSql.contains(";") || lowerSql.contains("--") || lowerSql.contains("/*") || lowerSql.contains("*/")) {
            throw new IllegalArgumentException("只允许执行单条且不带注释的 SQL 语句。");
        }

        if (sql.contains("%s") || sql.contains("%d") || sql.contains("?")) {
            throw new IllegalArgumentException("SQL 中仍包含未替换的占位符（如 %s、%d 或 ?），本次操作已被拦截。");
        }

        boolean isSelect = lowerSql.startsWith("select");
        boolean isInsert = lowerSql.startsWith("insert");
        boolean isUpdate = lowerSql.startsWith("update");
        boolean isDelete = lowerSql.startsWith("delete");

        if (!(isSelect || isInsert || isUpdate || isDelete)) {
            throw new IllegalArgumentException("仅支持 SELECT、INSERT、UPDATE 和 DELETE 语句。");
        }

        String[] forbiddenKeywords = {"drop", "truncate", "alter", "create", "grant", "revoke", "outfile"};
        for (String forbiddenKeyword : forbiddenKeywords) {
            if (lowerSql.contains(" " + forbiddenKeyword + " ") || lowerSql.startsWith(forbiddenKeyword + " ")) {
                throw new IllegalArgumentException("SQL 中包含禁止使用的关键字：" + forbiddenKeyword);
            }
        }

        List<String> referencedTables = extractReferencedTables(sql);
        if (referencedTables.isEmpty()) {
            throw new IllegalArgumentException("SQL 必须引用已知业务表。");
        }

        if (!CollectionUtils.isEmpty(allowedTableNames)) {
            List<String> invalidTables = new ArrayList<>();
            for (String tableName : referencedTables) {
                if (!allowedTableNames.contains(tableName)) {
                    invalidTables.add(tableName);
                }
            }
            if (!invalidTables.isEmpty()) {
                throw new IllegalArgumentException("SQL 包含未授权的数据表：" + String.join(", ", invalidTables));
            }
        }

        if ((isInsert || isUpdate || isDelete) && !allowWrite) {
            throw new IllegalArgumentException("当前为只读模式，请先开启“允许数据库写操作”开关后再重试。");
        }

        if ((isUpdate || isDelete) && !lowerSql.contains(" where ")) {
            throw new IllegalArgumentException("UPDATE 和 DELETE 语句必须包含 WHERE 条件。");
        }
    }

    private List<String> extractReferencedTables(String sql) {
        Matcher matcher = TABLE_NAME_PATTERN.matcher(sql);
        Set<String> tableNames = new LinkedHashSet<>();
        while (matcher.find()) {
            tableNames.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(tableNames);
    }

    private Map<String, Object> executeSql(String sql) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", sql);

        String lowerSql = sql.toLowerCase(Locale.ROOT);
        String operation = detectOperation(lowerSql);
        String tableName = extractPrimaryTable(sql);
        result.put("operation", operation);
        result.put("table", tableName);

        if ("select".equals(operation)) {
            List<Map<String, Object>> rows = sanitizeRows(jdbcTemplate.queryForList(sql));
            int previewRowLimit = resolvePreviewRowLimit(sql, rows.size());
            result.put("type", "query");
            result.put("success", true);
            result.put("rowCount", rows.size());
            result.put("rows", rows.size() > previewRowLimit ? rows.subList(0, previewRowLimit) : rows);
            result.put("summary", buildQuerySummary(tableName, rows, sql));
            if (rows.size() > previewRowLimit) {
                result.put("truncated", true);
            }
            return result;
        }

        int affectedRows;
        if (lowerSql.startsWith("insert")) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            affectedRows = jdbcTemplate.update(connection -> connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), keyHolder);
            result.put("type", "write");
            result.put("success", true);
            result.put("affectedRows", affectedRows);
            if (keyHolder.getKey() != null) {
                result.put("generatedKey", keyHolder.getKey());
            }
            result.put("summary", buildWriteSummary(operation, tableName, affectedRows, keyHolder.getKey()));
            return result;
        }

        affectedRows = jdbcTemplate.update(sql);
        result.put("type", "write");
        result.put("success", true);
        result.put("affectedRows", affectedRows);
        result.put("summary", buildWriteSummary(operation, tableName, affectedRows, null));
        return result;
    }

    private Map<String, Object> buildErrorResult(String sql, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", sql);
        result.put("type", "error");
        result.put("success", false);
        result.put("operation", detectOperation(sql.toLowerCase(Locale.ROOT)));
        result.put("table", extractPrimaryTable(sql));
        result.put("message", cleanupDatabaseMessage(message));
        result.put("summary", cleanupDatabaseMessage(message));
        return result;
    }

    private Map<String, Object> buildLogItem(ToolRequest toolRequest, String sql, boolean success, Map<String, Object> toolResult) {
        Map<String, Object> logItem = new LinkedHashMap<>();
        logItem.put("tool", toolRequest.getTool());
        logItem.put("reason", toolRequest.getReason());
        logItem.put("sql", sql);
        logItem.put("success", success);
        logItem.put("result", toolResult);
        return logItem;
    }

    private SchemaContext buildSchemaContext() {
        StringBuilder builder = new StringBuilder();
        Set<String> tableNames = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            try (ResultSet tables = metaData.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    tableNames.add(tableName.toLowerCase(Locale.ROOT));
                    builder.append("- ").append(tableName);

                    List<String> primaryKeys = loadPrimaryKeys(metaData, catalog, tableName);
                    if (!primaryKeys.isEmpty()) {
                        builder.append(" [PK: ").append(String.join(", ", primaryKeys)).append("]");
                    }

                    builder.append(": ");
                    List<String> columns = new ArrayList<>();
                    try (ResultSet columnSet = metaData.getColumns(catalog, null, tableName, "%")) {
                        while (columnSet.next()) {
                            String columnName = columnSet.getString("COLUMN_NAME");
                            String typeName = columnSet.getString("TYPE_NAME");
                            boolean nullable = columnSet.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                            columns.add(columnName + "(" + typeName + "," + (nullable ? "nullable" : "not null") + ")");
                        }
                    }
                    builder.append(String.join(", ", columns)).append("\n");
                }
            }
        } catch (SQLException e) {
            log.error("Failed to read database schema", e);
            builder.append("Schema snapshot unavailable.");
        }
        return new SchemaContext(builder.toString(), tableNames);
    }

    private List<String> loadPrimaryKeys(DatabaseMetaData metaData, String catalog, String tableName) throws SQLException {
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet primaryKeySet = metaData.getPrimaryKeys(catalog, null, tableName)) {
            while (primaryKeySet.next()) {
                primaryKeys.add(primaryKeySet.getString("COLUMN_NAME"));
            }
        }
        return primaryKeys;
    }

    private String detectOperation(String lowerSql) {
        if (lowerSql.startsWith("select")) {
            return "select";
        }
        if (lowerSql.startsWith("insert")) {
            return "insert";
        }
        if (lowerSql.startsWith("update")) {
            return "update";
        }
        if (lowerSql.startsWith("delete")) {
            return "delete";
        }
        return "unknown";
    }

    private String extractPrimaryTable(String sql) {
        List<String> tables = extractReferencedTables(sql);
        return tables.isEmpty() ? "" : tables.get(0);
    }

    private List<Map<String, Object>> sanitizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sanitizedRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> sanitizedRow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (shouldHideField(entry.getKey())) {
                    continue;
                }
                sanitizedRow.put(entry.getKey(), sanitizeFieldValue(entry.getKey(), entry.getValue()));
            }
            sanitizedRows.add(sanitizedRow);
        }
        return sanitizedRows;
    }

    private boolean shouldHideField(String fieldName) {
        String normalized = normalizeFieldKey(fieldName);
        return "password".equals(normalized) || "openid".equals(normalized);
    }

    private Object sanitizeFieldValue(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        String normalized = normalizeFieldKey(fieldName);
        String text = String.valueOf(value);
        if ("idnumber".equals(normalized) && text.length() >= 8) {
            return text.substring(0, 3) + "********" + text.substring(text.length() - 4);
        }
        if ("phone".equals(normalized) && text.length() == 11) {
            return text.substring(0, 3) + "****" + text.substring(7);
        }
        return value;
    }

    private int resolvePreviewRowLimit(String sql, int rowCount) {
        if (rowCount <= 0) {
            return 0;
        }
        int previewLimit = MAX_PREVIEW_ROWS;
        Matcher matcher = LIMIT_PATTERN.matcher(sql);
        if (matcher.find()) {
            String limitValue = matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
            try {
                previewLimit = Math.min(MAX_PREVIEW_ROWS, Math.max(1, Integer.parseInt(limitValue)));
            } catch (NumberFormatException ignored) {
                previewLimit = MAX_PREVIEW_ROWS;
            }
        }
        return Math.min(previewLimit, rowCount);
    }

    private String buildQuerySummary(String tableName, List<Map<String, Object>> rows, String sql) {
        String tableLabel = formatTableLabel(tableName);
        if (CollectionUtils.isEmpty(rows)) {
            return StringUtils.hasText(tableLabel) ? "未查询到" + tableLabel + "相关数据。" : "未查询到相关数据。";
        }
        if (isCountQueryResult(rows)) {
            return buildCountQuerySummary(tableName, rows, sql);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("已查询到").append(rows.size()).append("条");
        builder.append(StringUtils.hasText(tableLabel) ? tableLabel + "记录" : "记录");

        List<String> highlights = buildRowHighlights(tableName, rows);
        if (!highlights.isEmpty()) {
            builder.append("。例如：").append(String.join("；", highlights)).append("。");
        } else {
            builder.append("。");
        }
        return builder.toString();
    }

    private boolean isCountQueryResult(List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(rows) || rows.size() != 1) {
            return false;
        }
        Map<String, Object> firstRow = rows.get(0);
        if (firstRow.size() != 1) {
            return false;
        }
        Map.Entry<String, Object> entry = firstRow.entrySet().iterator().next();
        if (!(entry.getValue() instanceof Number)) {
            return false;
        }
        String normalizedField = normalizeFieldKey(entry.getKey());
        return normalizedField.contains("count") || normalizedField.contains("total");
    }

    private String buildCountQuerySummary(String tableName, List<Map<String, Object>> rows, String sql) {
        Map<String, Object> firstRow = rows.get(0);
        Map.Entry<String, Object> entry = firstRow.entrySet().iterator().next();
        long count = ((Number) entry.getValue()).longValue();
        String normalizedTable = tableName == null ? "" : tableName.toLowerCase(Locale.ROOT);
        String normalizedSql = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        if ("orders".equals(normalizedTable)
                && (normalizedSql.contains("curdate()") || normalizedSql.contains("current_date"))) {
            return "今天的订单数量为 " + count + " 单。";
        }
        String tableLabel = formatTableLabel(tableName);
        if (StringUtils.hasText(tableLabel)) {
            return "共查询到 " + count + " 条" + tableLabel + "记录。";
        }
        return "共查询到 " + count + " 条记录。";
    }

    private List<String> buildRowHighlights(String tableName, List<Map<String, Object>> rows) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < 3; i++) {
            Map<String, Object> row = rows.get(i);
            List<String> parts = new ArrayList<>();
            appendHighlightPart(parts, tableName, row, "item_type");
            appendHighlightPart(parts, tableName, row, "item_name");
            appendHighlightPart(parts, tableName, row, "sales_count");
            appendHighlightPart(parts, tableName, row, "name");
            appendHighlightPart(parts, tableName, row, "username");
            appendHighlightPart(parts, tableName, row, "number");
            appendHighlightPart(parts, tableName, row, "id");
            appendHighlightPart(parts, tableName, row, "phone");
            appendHighlightPart(parts, tableName, row, "status");
            if (!parts.isEmpty()) {
                highlights.add(String.join("，", parts));
            }
        }
        return highlights;
    }

    private void appendHighlightPart(List<String> parts, String tableName, Map<String, Object> row, String preferredField) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String normalized = normalizeFieldKey(entry.getKey());
            if (!preferredField.equals(normalized) || entry.getValue() == null) {
                continue;
            }
            parts.add(formatFieldLabel(entry.getKey()) + "：" + formatFieldValue(tableName, entry.getKey(), entry.getValue()));
            return;
        }
    }

    private String buildWriteSummary(String operation, String tableName, int affectedRows, Number generatedKey) {
        StringBuilder builder = new StringBuilder();
        builder.append(buildOperationLabel(operation, tableName)).append("成功，影响 ").append(affectedRows).append(" 条记录");
        if (generatedKey != null) {
            builder.append("，新记录编号为 ").append(generatedKey);
        }
        builder.append("。");
        return builder.toString();
    }

    private String buildOperationLabel(String operation, String tableName) {
        String tableLabel = formatTableLabel(tableName);
        String suffix = StringUtils.hasText(tableLabel) ? tableLabel : "记录";
        if ("insert".equals(operation)) {
            return "新增" + suffix;
        }
        if ("update".equals(operation)) {
            return "更新" + suffix;
        }
        if ("delete".equals(operation)) {
            return "删除" + suffix;
        }
        if ("select".equals(operation)) {
            return "查询" + suffix;
        }
        return "数据库操作";
    }

    private String formatTableLabel(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return "";
        }
        String normalized = tableName.toLowerCase(Locale.ROOT);
        if ("employee".equals(normalized)) {
            return "员工";
        }
        if ("orders".equals(normalized)) {
            return "订单";
        }
        if ("order_detail".equals(normalized)) {
            return "订单明细";
        }
        if ("dish".equals(normalized)) {
            return "菜品";
        }
        if ("category".equals(normalized)) {
            return "分类";
        }
        if ("setmeal".equals(normalized)) {
            return "套餐";
        }
        if ("shopping_cart".equals(normalized)) {
            return "购物车";
        }
        if ("address_book".equals(normalized)) {
            return "地址簿";
        }
        if ("user".equals(normalized)) {
            return "用户";
        }
        return tableName;
    }

    private String cleanupDatabaseMessage(String rawMessage) {
        if (!StringUtils.hasText(rawMessage)) {
            return "数据库执行失败，请检查输入信息后重试。";
        }
        String cleanedMessage = rawMessage.replaceAll("(?i)PreparedStatementCallback;\\s*", "")
                .replaceAll("(?i)StatementCallback;\\s*", "")
                .replaceAll("(?i)SQL \\[[^\\]]*];\\s*", "")
                .replaceAll("(?i)nested exception is [^:]+:\\s*", "")
                .trim();
        String normalized = cleanedMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("unknown column")) {
            return "SQL 引用了不存在的字段，本次数据库操作已被拦截。我已经保留了失败原因，你可以重新描述需求后再试一次。";
        }
        if (normalized.contains("bad sql grammar")
                || normalized.contains("you have an error in your sql syntax")) {
            if (normalized.contains("order by") && normalized.contains("limit")) {
                return "SQL 语法有误：排序条件的位置不正确，ORDER BY 必须放在 LIMIT 前面。这个问题已修复，请重新发送同样的查询。";
            }
            return "SQL 语法有误，本次数据库操作已被拦截。请重新发送一次查询或换一种更明确的描述。";
        }
        if (normalized.contains("unknown column")) {
            return "SQL 引用了不存在的字段，本次数据库操作已被拦截。我已经保留了失败原因，你可以重新描述需求后再试一次。";
        }
        return cleanedMessage;
    }

    private String formatFieldLabel(String fieldName) {
        String normalized = normalizeFieldKey(fieldName);
        if ("id".equals(normalized)) {
            return "编号";
        }
        if ("name".equals(normalized)) {
            return "姓名";
        }
        if ("username".equals(normalized)) {
            return "账号";
        }
        if ("itemtype".equals(normalized)) {
            return "类型";
        }
        if ("itemname".equals(normalized)) {
            return "名称";
        }
        if ("salescount".equals(normalized)) {
            return "销量";
        }
        if ("salesrank".equals(normalized)) {
            return "排名";
        }
        if ("categoryname".equals(normalized)) {
            return "分类";
        }
        if ("phone".equals(normalized)) {
            return "手机号";
        }
        if ("number".equals(normalized)) {
            return "订单号";
        }
        if ("itemtype".equals(normalized)) {
            String text = "";
            if ("dish".equals(text)) {
                return "菜品";
            }
            if ("setmeal".equals(text)) {
                return "套餐";
            }
        }
        if ("status".equals(normalized)) {
            return "状态";
        }
        if ("sex".equals(normalized)) {
            return "性别";
        }
        if ("idnumber".equals(normalized)) {
            return "身份证号";
        }
        if ("createtime".equals(normalized)) {
            return "创建时间";
        }
        if ("updatetime".equals(normalized)) {
            return "更新时间";
        }
        if ("ordertime".equals(normalized)) {
            return "下单时间";
        }
        if ("checkouttime".equals(normalized)) {
            return "结账时间";
        }
        if ("amount".equals(normalized)) {
            return "金额";
        }
        if ("consignee".equals(normalized)) {
            return "收货人";
        }
        if ("address".equals(normalized)) {
            return "地址";
        }
        if ("sex".equals(normalized)) {
            String text = "";
            if ("1".equals(text)) {
                return "男";
            }
            if ("2".equals(text) || "0".equals(text)) {
                return "女";
            }
        }
        if ("paymethod".equals(normalized)) {
            return "支付方式";
        }
        if ("paystatus".equals(normalized)) {
            return "支付状态";
        }
        return fieldName;
    }

    private String formatFieldValue(String tableName, String fieldName, Object value) {
        if (value == null) {
            return "-";
        }
        String normalized = normalizeFieldKey(fieldName);
        String normalizedTable = tableName == null ? "" : tableName.toLowerCase(Locale.ROOT);
        if ("status".equals(normalized)) {
            String text = String.valueOf(value);
            if ("orders".equals(normalizedTable)) {
                if ("1".equals(text)) {
                    return "待付款";
                }
                if ("2".equals(text)) {
                    return "待接单";
                }
                if ("3".equals(text)) {
                    return "已接单";
                }
                if ("4".equals(text)) {
                    return "派送中";
                }
                if ("5".equals(text)) {
                    return "已完成";
                }
                if ("6".equals(text)) {
                    return "已取消";
                }
            }
            if ("1".equals(text)) {
                return "启用";
            }
            if ("0".equals(text)) {
                return "禁用";
            }
        }
        if ("paymethod".equals(normalized)) {
            String text = String.valueOf(value);
            if ("1".equals(text)) {
                return "微信";
            }
            if ("2".equals(text)) {
                return "支付宝";
            }
        }
        if ("paystatus".equals(normalized)) {
            String text = String.valueOf(value);
            if ("1".equals(text)) {
                return "已支付";
            }
            if ("0".equals(text)) {
                return "未支付";
            }
        }
        return String.valueOf(value);
    }

    private boolean shouldStopToolLoop(List<Map<String, Object>> executionLogs, String sql) {
        if (executionLogs.size() < 2) {
            return false;
        }
        Object previousSql = executionLogs.get(executionLogs.size() - 2).get("sql");
        return previousSql != null && sql.equalsIgnoreCase(String.valueOf(previousSql));
    }

    private boolean shouldStopOnError(Map<String, Object> toolResult) {
        if (toolResult == null || !Boolean.FALSE.equals(toolResult.get("success"))) {
            return false;
        }
        String message = String.valueOf(toolResult.get("message"));
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("unknown column")
                || normalized.contains("占位符")
                || normalized.contains("%s")
                || normalized.contains("%d");
    }

    private List<Map<String, Object>> deduplicateExecutionLogs(List<Map<String, Object>> executionLogs) {
        if (CollectionUtils.isEmpty(executionLogs)) {
            return executionLogs;
        }
        List<Map<String, Object>> normalizedLogs = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (Map<String, Object> log : executionLogs) {
            String logKey = buildExecutionLogKey(log);
            if (seenKeys.add(logKey)) {
                normalizedLogs.add(log);
            }
        }
        return normalizedLogs;
    }

    private String buildExecutionLogKey(Map<String, Object> log) {
        Map<?, ?> result = log.get("result") instanceof Map ? (Map<?, ?>) log.get("result") : null;
        String sql = String.valueOf(log.get("sql"));
        String success = String.valueOf(log.get("success"));
        String resultType = result == null ? "" : String.valueOf(result.get("type"));
        String table = result == null ? "" : String.valueOf(result.get("table"));
        String rowCount = result == null ? "" : String.valueOf(result.get("rowCount"));
        String affectedRows = result == null ? "" : String.valueOf(result.get("affectedRows"));
        String summary = result == null ? "" : String.valueOf(result.get("summary"));
        return String.join("|", sql, success, resultType, table, rowCount, affectedRows, summary);
    }

    private String normalizeFieldKey(String fieldName) {
        return fieldName == null ? "" : fieldName.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private String buildFallbackAnswer(List<Map<String, Object>> executionLogs) {
        if (CollectionUtils.isEmpty(executionLogs)) {
            return "智能客服暂时无法给出明确答复，请稍后重试。";
        }

        Map<String, Object> lastLog = executionLogs.get(executionLogs.size() - 1);
        Object resultObject = lastLog.get("result");
        if (Boolean.FALSE.equals(lastLog.get("success")) && resultObject instanceof Map) {
            Object message = ((Map<?, ?>) resultObject).get("message");
            if (message != null) {
                return String.valueOf(message);
            }
        }

        if (resultObject instanceof Map) {
            Map<?, ?> result = (Map<?, ?>) resultObject;
            Object summary = result.get("summary");
            if (summary != null && StringUtils.hasText(String.valueOf(summary))) {
                return String.valueOf(summary);
            }
            if ("write".equals(result.get("type"))) {
                return "数据库操作已执行，影响 " + result.get("affectedRows") + " 条记录。";
            }
            if ("query".equals(result.get("type"))) {
                return "已完成数据库查询，共返回 " + result.get("rowCount") + " 条记录。";
            }
        }

        return "智能客服已完成数据库操作，请继续告诉我下一步需求。";
    }

    private boolean shouldUseFallbackAnswer(String finalAnswer) {
        String normalized = finalAnswer.toLowerCase(Locale.ROOT);
        return normalized.contains("xiaowei take out")
                || normalized.contains("json")
                || finalAnswer.contains("执行日志")
                || finalAnswer.contains("查看日志")
                || finalAnswer.contains("SQL")
                || finalAnswer.contains("写库")
                || finalAnswer.contains("权限已关闭")
                || finalAnswer.contains("开启写库")
                || finalAnswer.contains("请查看详情");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    @Data
    private static class ToolRequest {
        private String type;
        private String tool;
        private String sql;
        private String reason;
    }

    @Data
    @AllArgsConstructor
    private static class ConversationMessage {
        private String role;
        private String content;
    }

    @Data
    @AllArgsConstructor
    private static class SchemaContext {
        private String snapshot;
        private Set<String> tableNames;
    }
}
