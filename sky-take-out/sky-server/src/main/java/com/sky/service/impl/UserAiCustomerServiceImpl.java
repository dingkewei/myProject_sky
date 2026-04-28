package com.sky.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.dto.AiCustomerServiceChatDTO;
import com.sky.dto.AiCustomerServiceMessageDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.Dish;
import com.sky.entity.Orders;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.properties.OllamaProperties;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.service.ShoppingCartService;
import com.sky.service.UserAiCustomerService;
import com.sky.vo.AiCustomerServiceChatVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class UserAiCustomerServiceImpl implements UserAiCustomerService {

    private static final Pattern THINK_PATTERN = Pattern.compile("(?s)<think>.*?</think>");
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("(?s)```json\\s*(\\{.*?})\\s*```");
    private static final Pattern RAW_JSON_PATTERN = Pattern.compile("(?s)(\\{\\s*\"type\"\\s*:\\s*\"(?:tool|response|message)\".*})");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern CHINESE_NUMBER_PATTERN = Pattern.compile("([一二两三四五六七八九十])");
    private static final Pattern QUANTITY_WITH_UNIT_PATTERN = Pattern.compile("(\\d+|[一二两三四五六七八九十]+)\\s*(份|个|瓶|杯|罐|听|碗|盒|套餐)?");
    private static final Pattern PLACE_ORDER_SEGMENT_PATTERN = Pattern.compile("(?:下单|点单|帮我下单|帮我点单|来一份|来个|来一杯)");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(%s|%d|\\?)");
    private static final int DEFAULT_HISTORY_LIMIT = 10;
    private static final int MAX_HISTORY_LIMIT = 20;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private OllamaProperties ollamaProperties;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ShoppingCartService shoppingCartService;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public AiCustomerServiceChatVO chat(AiCustomerServiceChatDTO chatDTO) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long startTime = System.currentTimeMillis();
        boolean allowWrite = chatDTO.getAllowWrite() == null || Boolean.TRUE.equals(chatDTO.getAllowWrite());
        List<ConversationMessage> conversation = buildConversation(chatDTO);
        String latestUserMessage = getLatestUserMessage(conversation);
        List<Map<String, Object>> executionLogs = new ArrayList<>();

        log.info("[USER-AI:{}] chat start | model={} | allowWrite={} | historySize={}",
                traceId, ollamaProperties.getModel(), allowWrite, conversation.size());
        logBlock(traceId, "latest user message", latestUserMessage);

        String prompt = buildPrompt(conversation, allowWrite);
        logBlock(traceId, "prompt", prompt);
        String modelOutput = callOllama(prompt);
        logBlock(traceId, "model output", modelOutput);
        String sanitizedOutput = sanitizeModelOutput(modelOutput);
        logBlock(traceId, "sanitized output", sanitizedOutput);

        ToolRequest parsedToolRequest = parseToolRequest(sanitizedOutput);
        ToolRequest inferredToolRequest = inferToolRequest(latestUserMessage, allowWrite);
        ToolRequest toolRequest = shouldPreferInferredToolRequest(parsedToolRequest, inferredToolRequest)
                ? inferredToolRequest
                : parsedToolRequest;

        if (toolRequest != null && toolRequest == inferredToolRequest && inferredToolRequest != null) {
            logBlock(traceId, "inferred tool request from user intent", toJson(inferredToolRequest));
        }
        logBlock(traceId, "parsed tool request", toolRequest == null ? "<none>" : toJson(toolRequest));

        if (toolRequest == null) {
            String directAnswer = normalizeDirectAnswer(sanitizedOutput);
            logBlock(traceId, "direct answer", directAnswer);
            log.info("[USER-AI:{}] chat end without tool | totalCostMs={}", traceId, System.currentTimeMillis() - startTime);
            return AiCustomerServiceChatVO.builder()
                    .answer(directAnswer)
                    .usedDatabase(false)
                    .executionLogs(executionLogs)
                    .build();
        }

        Map<String, Object> toolResult = executeTool(toolRequest, latestUserMessage, allowWrite);
        executionLogs.add(buildExecutionLog(toolRequest, toolResult));
        String finalAnswer = String.valueOf(toolResult.getOrDefault("summary", "智能客服已处理完成。"));

        logBlock(traceId, "tool result", toJson(toolResult));
        logBlock(traceId, "final answer", finalAnswer);
        log.info("[USER-AI:{}] chat end | usedTool={} | success={} | totalCostMs={}",
                traceId, toolRequest.getTool(), toolResult.get("success"), System.currentTimeMillis() - startTime);

        return AiCustomerServiceChatVO.builder()
                .answer(finalAnswer)
                .usedDatabase(true)
                .executionLogs(executionLogs)
                .build();
    }

    private List<ConversationMessage> buildConversation(AiCustomerServiceChatDTO chatDTO) {
        List<ConversationMessage> messages = new ArrayList<>();
        if (!CollectionUtils.isEmpty(chatDTO.getHistory())) {
            for (AiCustomerServiceMessageDTO item : chatDTO.getHistory()) {
                if (!StringUtils.hasText(item.getContent())) {
                    continue;
                }
                String role = StringUtils.hasText(item.getRole())
                        ? item.getRole().trim().toLowerCase(Locale.ROOT)
                        : "user";
                messages.add(new ConversationMessage(role, item.getContent().trim()));
            }
        }
        if (StringUtils.hasText(chatDTO.getMessage())) {
            messages.add(new ConversationMessage("user", chatDTO.getMessage().trim()));
        }
        return messages;
    }

    private String getLatestUserMessage(List<ConversationMessage> conversation) {
        for (int i = conversation.size() - 1; i >= 0; i--) {
            ConversationMessage message = conversation.get(i);
            if ("user".equalsIgnoreCase(message.getRole()) && StringUtils.hasText(message.getContent())) {
                return message.getContent().trim();
            }
        }
        return "";
    }

    private String buildPrompt(List<ConversationMessage> conversation, boolean allowWrite) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are the AI customer service assistant for 小威外卖's user app.\n")
                .append("Always answer the final user-facing response in Chinese.\n")
                .append("Use the same warm, practical tone as the merchant-side AI assistant.\n")
                .append("You can help the current user query their own orders and place orders.\n")
                .append("Current order write permission: ").append(allowWrite ? "ENABLED" : "DISABLED").append(".\n")
                .append("If a tool is needed, return only one JSON object with no markdown and no extra explanation.\n")
                .append("Supported tools:\n")
                .append("1. Query current orders: {\"type\":\"tool\",\"tool\":\"query_current_orders\",\"limit\":3,\"reason\":\"short reason\"}\n")
                .append("2. Query history orders: {\"type\":\"tool\",\"tool\":\"query_history_orders\",\"limit\":10,\"reason\":\"short reason\"}\n")
                .append("3. Place order: {\"type\":\"tool\",\"tool\":\"place_order\",\"useDefaultAddress\":true,\"remark\":\"optional remark\",\"items\":[{\"name\":\"商品名\",\"quantity\":1,\"kind\":\"dish_or_setmeal\",\"dishFlavor\":\"optional\"}],\"reason\":\"short reason\"}\n")
                .append("Rules:\n")
                .append("- Never output SQL.\n")
                .append("- Never expose tool names, internal JSON, logs, or schema details to the user.\n")
                .append("- For current order status queries, only return the current logged-in user's own active orders.\n")
                .append("- For history queries, prefer the most recent 10 orders unless the user asks another number.\n")
                .append("- For placing orders, use the default address unless the user clearly asks otherwise.\n")
                .append("- If write permission is disabled, never choose place_order.\n")
                .append("- If the user asks to order food, extract item names and quantities as accurately as possible.\n")
                .append("- If no tool is needed, reply directly in Chinese.\n")
                .append("Conversation:\n");
        for (ConversationMessage message : conversation) {
            builder.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
        builder.append("assistant:");
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
            return "抱歉，我暂时没有收到模型返回内容。";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("response").asText("");
        } catch (JsonProcessingException e) {
            log.error("Failed to parse user ai model response: {}", responseBody, e);
            return "抱歉，我暂时无法解析模型返回内容。";
        }
    }

    private String sanitizeModelOutput(String modelOutput) {
        if (!StringUtils.hasText(modelOutput)) {
            return "";
        }
        String sanitized = THINK_PATTERN.matcher(modelOutput).replaceAll("").trim();
        return sanitized.replaceAll("(?s)^```[a-zA-Z]*\\s*|\\s*```$", "").trim();
    }

    private ToolRequest parseToolRequest(String content) {
        String jsonPayload = extractJsonPayload(content);
        if (!StringUtils.hasText(jsonPayload)) {
            return null;
        }
        try {
            ToolRequest toolRequest = objectMapper.readValue(jsonPayload, ToolRequest.class);
            if (!"tool".equalsIgnoreCase(toolRequest.getType()) || !StringUtils.hasText(toolRequest.getTool())) {
                return null;
            }
            return toolRequest;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse user ai tool JSON: {}", jsonPayload, e);
            return null;
        }
    }

    private String extractJsonPayload(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        Matcher jsonBlockMatcher = JSON_BLOCK_PATTERN.matcher(content);
        if (jsonBlockMatcher.find()) {
            return jsonBlockMatcher.group(1);
        }
        Matcher rawJsonMatcher = RAW_JSON_PATTERN.matcher(content);
        if (rawJsonMatcher.find()) {
            return rawJsonMatcher.group(1);
        }
        return null;
    }

    private String normalizeDirectAnswer(String content) {
        if (!StringUtils.hasText(content)) {
            return "你好，我是小威外卖智能客服。你可以问我订单状态、历史订单，或者直接让我帮你下单。";
        }
        String jsonPayload = extractJsonPayload(content);
        if (!StringUtils.hasText(jsonPayload)) {
            return content.trim();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            String type = root.path("type").asText("");
            if (("response".equalsIgnoreCase(type) || "message".equalsIgnoreCase(type)) && root.has("content")) {
                return root.path("content").asText("");
            }
            if (root.has("reason")) {
                return root.path("reason").asText("");
            }
        } catch (JsonProcessingException ignored) {
            return content.trim();
        }
        return content.trim();
    }

    private ToolRequest inferToolRequest(String latestUserMessage, boolean allowWrite) {
        if (!StringUtils.hasText(latestUserMessage)) {
            return null;
        }
        String normalizedMessage = normalizeText(latestUserMessage);
        if (containsAny(normalizedMessage, "历史订单", "订单历史", "以前订单", "过去订单")) {
            return ToolRequest.history(resolveRequestedLimit(latestUserMessage), "查询用户历史订单");
        }
        if (containsAny(normalizedMessage, "当前订单", "现在订单", "订单状态", "配送到哪", "订单到哪", "正在配送", "最近订单状态")) {
            return ToolRequest.current(resolveRequestedLimit(latestUserMessage), "查询用户当前订单状态");
        }
        if (allowWrite && containsAny(normalizedMessage, "下单", "点单", "帮我买", "帮我点", "来一份", "来个", "来一杯")) {
            ToolRequest toolRequest = ToolRequest.placeOrder("为用户智能下单");
            toolRequest.setItems(extractItemsFromMessage(latestUserMessage));
            toolRequest.setUseDefaultAddress(true);
            toolRequest.setRemark(extractRemark(latestUserMessage));
            return toolRequest;
        }
        return null;
    }

    private boolean shouldPreferInferredToolRequest(ToolRequest parsedToolRequest, ToolRequest inferredToolRequest) {
        if (inferredToolRequest == null) {
            return false;
        }
        if (parsedToolRequest == null || !StringUtils.hasText(parsedToolRequest.getTool())) {
            return true;
        }
        if ("place_order".equals(inferredToolRequest.getTool())) {
            return CollectionUtils.isEmpty(parsedToolRequest.getItems()) || containsPlaceholders(parsedToolRequest);
        }
        return false;
    }

    private boolean containsPlaceholders(ToolRequest toolRequest) {
        if (toolRequest == null || CollectionUtils.isEmpty(toolRequest.getItems())) {
            return false;
        }
        for (ToolItem item : toolRequest.getItems()) {
            if (item == null || !StringUtils.hasText(item.getName())) {
                return true;
            }
            if (PLACEHOLDER_PATTERN.matcher(item.getName()).find()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> executeTool(ToolRequest toolRequest, String latestUserMessage, boolean allowWrite) {
        if ("query_current_orders".equals(toolRequest.getTool())) {
            return queryCurrentOrders(toolRequest.getLimit());
        }
        if ("query_history_orders".equals(toolRequest.getTool())) {
            return queryHistoryOrders(toolRequest.getLimit());
        }
        if ("place_order".equals(toolRequest.getTool())) {
            return placeOrder(toolRequest, latestUserMessage, allowWrite);
        }
        return buildErrorResult(toolRequest.getTool(), "暂不支持这个用户侧智能客服动作。");
    }

    private Map<String, Object> queryCurrentOrders(Integer limit) {
        int resolvedLimit = clampLimit(limit);
        PageResult pageResult = orderService.pageQuery(1, resolvedLimit, null);
        List<OrderVO> allOrders = castOrders(pageResult);
        List<OrderVO> currentOrders = new ArrayList<>();
        for (OrderVO order : allOrders) {
            if (order != null && isCurrentOrderStatus(order.getStatus())) {
                currentOrders.add(order);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "query");
        result.put("tool", "query_current_orders");
        result.put("success", true);
        result.put("rowCount", currentOrders.size());
        result.put("rows", toOrderRows(currentOrders));
        result.put("summary", buildCurrentOrdersSummary(currentOrders));
        return result;
    }

    private Map<String, Object> queryHistoryOrders(Integer limit) {
        int resolvedLimit = clampLimit(limit);
        PageResult pageResult = orderService.pageQuery(1, resolvedLimit, null);
        List<OrderVO> orders = castOrders(pageResult);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "query");
        result.put("tool", "query_history_orders");
        result.put("success", true);
        result.put("rowCount", orders.size());
        result.put("rows", toOrderRows(orders));
        result.put("summary", buildHistoryOrdersSummary(orders));
        return result;
    }

    private Map<String, Object> placeOrder(ToolRequest toolRequest, String latestUserMessage, boolean allowWrite) {
        if (!allowWrite) {
            return buildErrorResult("place_order", "当前未开启下单权限，请先允许智能客服执行下单操作后再试。");
        }

        List<ToolItem> requestedItems = CollectionUtils.isEmpty(toolRequest.getItems())
                ? extractItemsFromMessage(latestUserMessage)
                : toolRequest.getItems();
        if (CollectionUtils.isEmpty(requestedItems)) {
            return buildErrorResult("place_order", "我还没识别出你想下单的商品，请直接告诉我商品名称，例如：帮我下单鱼香肉丝和可乐。");
        }

        AddressBook defaultAddress = getDefaultAddress();
        if (defaultAddress == null) {
            return buildErrorResult("place_order", "当前没有找到默认地址，请先在小程序地址簿里设置默认地址后，再让我帮你下单。");
        }

        List<ResolvedOrderItem> resolvedItems = new ArrayList<>();
        List<String> unresolvedNames = new ArrayList<>();
        for (ToolItem requestedItem : requestedItems) {
            ResolvedOrderItem resolvedItem = resolveOrderItem(requestedItem);
            if (resolvedItem == null) {
                unresolvedNames.add(requestedItem == null ? "" : requestedItem.getName());
            } else {
                resolvedItems.add(resolvedItem);
            }
        }

        if (!unresolvedNames.isEmpty()) {
            return buildErrorResult("place_order", "这些商品我暂时没有匹配到：" + String.join("、", removeBlankValues(unresolvedNames)) + "。你可以换一个更准确的商品名称再试一次。");
        }

        List<ShoppingCart> originalCart = cloneCartItems(shoppingCartService.showShoppingCart());
        try {
            shoppingCartService.clean();
            for (ResolvedOrderItem item : resolvedItems) {
                addResolvedItemToCart(item);
            }

            List<ShoppingCart> aiCart = shoppingCartService.showShoppingCart();
            BigDecimal totalAmount = calculateCartAmount(aiCart);

            OrdersSubmitDTO submitDTO = new OrdersSubmitDTO();
            submitDTO.setAddressBookId(defaultAddress.getId());
            submitDTO.setPayMethod(1);
            submitDTO.setRemark(StringUtils.hasText(toolRequest.getRemark()) ? toolRequest.getRemark() : extractRemark(latestUserMessage));
            submitDTO.setDeliveryStatus(1);
            submitDTO.setTablewareStatus(1);
            submitDTO.setTablewareNumber(0);
            submitDTO.setPackAmount(0);
            submitDTO.setAmount(totalAmount);

            OrderSubmitVO submitVO = orderService.submitOrder(submitDTO);
            restoreCart(originalCart);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "write");
            result.put("tool", "place_order");
            result.put("success", true);
            result.put("orderId", submitVO.getId());
            result.put("orderNumber", submitVO.getOrderNumber());
            result.put("amount", submitVO.getOrderAmount());
            result.put("items", toResolvedItemRows(resolvedItems));
            result.put("summary", buildPlaceOrderSummary(resolvedItems, defaultAddress, submitVO));
            return result;
        } catch (Exception ex) {
            log.warn("User ai place order failed", ex);
            shoppingCartService.clean();
            restoreCart(originalCart);
            return buildErrorResult("place_order", "下单没有成功，原因是：" + cleanupExceptionMessage(ex.getMessage()));
        }
    }

    private AddressBook getDefaultAddress() {
        AddressBook condition = AddressBook.builder()
                .userId(BaseContext.getCurrentId())
                .isDefault(1)
                .build();
        List<AddressBook> addressBooks = addressBookMapper.list(condition);
        if (CollectionUtils.isEmpty(addressBooks)) {
            return null;
        }
        return addressBooks.get(0);
    }

    private ResolvedOrderItem resolveOrderItem(ToolItem requestedItem) {
        if (requestedItem == null || !StringUtils.hasText(requestedItem.getName())) {
            return null;
        }
        String requestedName = requestedItem.getName().trim();
        int quantity = normalizeQuantity(requestedItem.getQuantity());
        String kind = normalizeText(requestedItem.getKind());
        String dishFlavor = StringUtils.hasText(requestedItem.getDishFlavor()) ? requestedItem.getDishFlavor().trim() : null;

        if ("setmeal".equals(kind) || requestedName.contains("套餐")) {
            return resolveSetmeal(requestedName, quantity);
        }
        if ("dish".equals(kind) || "菜品".equals(kind)) {
            return resolveDish(requestedName, quantity, dishFlavor);
        }

        ResolvedOrderItem dish = resolveDish(requestedName, quantity, dishFlavor);
        ResolvedOrderItem setmeal = resolveSetmeal(requestedName, quantity);
        if (dish == null) {
            return setmeal;
        }
        if (setmeal == null) {
            return dish;
        }

        int dishScore = scoreMatch(dish.getName(), requestedName);
        int setmealScore = scoreMatch(setmeal.getName(), requestedName);
        return dishScore >= setmealScore ? dish : setmeal;
    }

    private ResolvedOrderItem resolveDish(String requestedName, int quantity, String dishFlavor) {
        Dish condition = Dish.builder()
                .name(requestedName)
                .status(1)
                .build();
        List<Dish> dishes = dishMapper.list(condition);
        Dish selected = selectBestDish(dishes, requestedName);
        if (selected == null) {
            return null;
        }
        return new ResolvedOrderItem(selected.getName(), "dish", selected.getId(), null, quantity, dishFlavor, selected.getPrice());
    }

    private ResolvedOrderItem resolveSetmeal(String requestedName, int quantity) {
        Setmeal condition = Setmeal.builder()
                .name(requestedName)
                .status(1)
                .build();
        List<Setmeal> setmeals = setmealMapper.list(condition);
        Setmeal selected = selectBestSetmeal(setmeals, requestedName);
        if (selected == null) {
            return null;
        }
        return new ResolvedOrderItem(selected.getName(), "setmeal", null, selected.getId(), quantity, null, selected.getPrice());
    }

    private Dish selectBestDish(List<Dish> dishes, String requestedName) {
        if (CollectionUtils.isEmpty(dishes)) {
            return null;
        }
        Dish best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Dish dish : dishes) {
            int score = scoreMatch(dish.getName(), requestedName);
            if (score > bestScore) {
                best = dish;
                bestScore = score;
            }
        }
        return best;
    }

    private Setmeal selectBestSetmeal(List<Setmeal> setmeals, String requestedName) {
        if (CollectionUtils.isEmpty(setmeals)) {
            return null;
        }
        Setmeal best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Setmeal setmeal : setmeals) {
            int score = scoreMatch(setmeal.getName(), requestedName);
            if (score > bestScore) {
                best = setmeal;
                bestScore = score;
            }
        }
        return best;
    }

    private int scoreMatch(String candidate, String requestedName) {
        String normalizedCandidate = normalizeText(candidate);
        String normalizedRequested = normalizeText(requestedName);
        if (!StringUtils.hasText(normalizedCandidate) || !StringUtils.hasText(normalizedRequested)) {
            return 0;
        }
        if (normalizedCandidate.equals(normalizedRequested)) {
            return 100;
        }
        if (normalizedCandidate.startsWith(normalizedRequested) || normalizedRequested.startsWith(normalizedCandidate)) {
            return 80;
        }
        if (normalizedCandidate.contains(normalizedRequested) || normalizedRequested.contains(normalizedCandidate)) {
            return 60;
        }
        return 10 - Math.abs(normalizedCandidate.length() - normalizedRequested.length());
    }

    private void addResolvedItemToCart(ResolvedOrderItem item) {
        ShoppingCartDTO shoppingCartDTO = new ShoppingCartDTO();
        shoppingCartDTO.setDishId(item.getDishId());
        shoppingCartDTO.setSetmealId(item.getSetmealId());
        shoppingCartDTO.setDishFlavor(item.getDishFlavor());

        for (int i = 0; i < item.getQuantity(); i++) {
            shoppingCartService.add(shoppingCartDTO);
        }
    }

    private List<ShoppingCart> cloneCartItems(List<ShoppingCart> shoppingCarts) {
        if (CollectionUtils.isEmpty(shoppingCarts)) {
            return new ArrayList<>();
        }
        List<ShoppingCart> clones = new ArrayList<>();
        for (ShoppingCart item : shoppingCarts) {
            clones.add(ShoppingCart.builder()
                    .name(item.getName())
                    .userId(item.getUserId())
                    .dishId(item.getDishId())
                    .setmealId(item.getSetmealId())
                    .dishFlavor(item.getDishFlavor())
                    .number(item.getNumber())
                    .amount(item.getAmount())
                    .image(item.getImage())
                    .createTime(item.getCreateTime())
                    .build());
        }
        return clones;
    }

    private void restoreCart(List<ShoppingCart> originalCart) {
        if (CollectionUtils.isEmpty(originalCart)) {
            return;
        }
        shoppingCartService.clean();
        for (ShoppingCart item : originalCart) {
            ShoppingCartDTO dto = new ShoppingCartDTO();
            dto.setDishId(item.getDishId());
            dto.setSetmealId(item.getSetmealId());
            dto.setDishFlavor(item.getDishFlavor());
            int quantity = item.getNumber() == null ? 1 : item.getNumber();
            for (int i = 0; i < quantity; i++) {
                shoppingCartService.add(dto);
            }
        }
    }

    private BigDecimal calculateCartAmount(List<ShoppingCart> shoppingCarts) {
        BigDecimal total = BigDecimal.ZERO;
        if (CollectionUtils.isEmpty(shoppingCarts)) {
            return total;
        }
        for (ShoppingCart item : shoppingCarts) {
            BigDecimal amount = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
            int number = item.getNumber() == null ? 0 : item.getNumber();
            total = total.add(amount.multiply(BigDecimal.valueOf(number)));
        }
        return total;
    }

    private List<Map<String, Object>> toOrderRows(List<OrderVO> orders) {
        if (CollectionUtils.isEmpty(orders)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderVO order : orders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", order.getId());
            row.put("orderNumber", order.getNumber());
            row.put("status", order.getStatus());
            row.put("statusLabel", formatOrderStatus(order.getStatus()));
            row.put("amount", order.getAmount());
            row.put("orderTime", order.getOrderTime() == null ? "" : order.getOrderTime().format(TIME_FORMATTER));
            row.put("address", safeText(order.getAddress()));
            row.put("consignee", safeText(order.getConsignee()));
            row.put("phone", safeText(order.getPhone()));
            row.put("dishes", buildOrderDishPreview(order));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> toResolvedItemRows(List<ResolvedOrderItem> items) {
        if (CollectionUtils.isEmpty(items)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResolvedOrderItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", item.getName());
            row.put("kind", item.getKind());
            row.put("quantity", item.getQuantity());
            row.put("dishFlavor", safeText(item.getDishFlavor()));
            row.put("unitPrice", item.getPrice());
            rows.add(row);
        }
        return rows;
    }

    private String buildCurrentOrdersSummary(List<OrderVO> orders) {
        if (CollectionUtils.isEmpty(orders)) {
            return "你当前没有进行中的订单。";
        }
        List<String> previews = new ArrayList<>();
        for (int i = 0; i < orders.size() && i < 3; i++) {
            OrderVO order = orders.get(i);
            previews.add("订单号" + safeText(order.getNumber()) + "，状态：" + formatOrderStatus(order.getStatus()) + "，商品：" + buildOrderDishPreview(order));
        }
        return "我帮你查到 " + orders.size() + " 笔进行中的订单。比如：" + String.join("；", previews) + "。";
    }

    private String buildHistoryOrdersSummary(List<OrderVO> orders) {
        if (CollectionUtils.isEmpty(orders)) {
            return "你目前还没有历史订单记录。";
        }
        List<String> previews = new ArrayList<>();
        for (int i = 0; i < orders.size() && i < 3; i++) {
            OrderVO order = orders.get(i);
            previews.add("订单号" + safeText(order.getNumber()) + "，下单时间：" + (order.getOrderTime() == null ? "-" : order.getOrderTime().format(TIME_FORMATTER)) + "，状态：" + formatOrderStatus(order.getStatus()));
        }
        return "我帮你查到最近 " + orders.size() + " 笔历史订单。比如：" + String.join("；", previews) + "。";
    }

    private String buildPlaceOrderSummary(List<ResolvedOrderItem> items, AddressBook defaultAddress, OrderSubmitVO submitVO) {
        List<String> itemTexts = new ArrayList<>();
        for (ResolvedOrderItem item : items) {
            itemTexts.add(item.getName() + " x" + item.getQuantity());
        }
        return "已经帮你下单成功。订单号：" + safeText(submitVO.getOrderNumber())
                + "，共 " + itemTexts.size() + " 种商品，商品明细：" + String.join("、", itemTexts)
                + "，配送地址：" + safeAddressLabel(defaultAddress)
                + "，订单金额：" + submitVO.getOrderAmount() + " 元。";
    }

    private String safeAddressLabel(AddressBook defaultAddress) {
        if (defaultAddress == null) {
            return "默认地址";
        }
        List<String> parts = new ArrayList<>();
        parts.add(safeText(defaultAddress.getProvinceName()));
        parts.add(safeText(defaultAddress.getCityName()));
        parts.add(safeText(defaultAddress.getDistrictName()));
        parts.add(safeText(defaultAddress.getDetail()));
        return String.join("", removeBlankValues(parts));
    }

    private String buildOrderDishPreview(OrderVO order) {
        if (order == null) {
            return "";
        }
        if (!CollectionUtils.isEmpty(order.getOrderDetailList())) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < order.getOrderDetailList().size() && i < 3; i++) {
                String name = safeText(order.getOrderDetailList().get(i).getName());
                Integer number = order.getOrderDetailList().get(i).getNumber();
                parts.add(name + " x" + (number == null ? 1 : number));
            }
            return String.join("、", parts);
        }
        return safeText(order.getOrderDishes());
    }

    @SuppressWarnings("unchecked")
    private List<OrderVO> castOrders(PageResult pageResult) {
        if (pageResult == null || CollectionUtils.isEmpty(pageResult.getRecords())) {
            return new ArrayList<>();
        }
        return (List<OrderVO>) pageResult.getRecords();
    }

    private boolean isCurrentOrderStatus(Integer status) {
        return Orders.PENDING_PAYMENT.equals(status)
                || Orders.TO_BE_CONFIRMED.equals(status)
                || Orders.CONFIRMED.equals(status)
                || Orders.DELIVERY_IN_PROGRESS.equals(status);
    }

    private Map<String, Object> buildExecutionLog(ToolRequest toolRequest, Map<String, Object> toolResult) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("tool", toolRequest.getTool());
        logEntry.put("reason", toolRequest.getReason());
        logEntry.put("success", toolResult.get("success"));
        logEntry.put("result", toolResult);
        return logEntry;
    }

    private Map<String, Object> buildErrorResult(String tool, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "error");
        result.put("tool", tool);
        result.put("success", false);
        result.put("message", message);
        result.put("summary", message);
        return result;
    }

    private int resolveRequestedLimit(String latestUserMessage) {
        Matcher matcher = NUMBER_PATTERN.matcher(latestUserMessage);
        if (matcher.find()) {
            return clampLimit(Integer.parseInt(matcher.group(1)));
        }
        Matcher chineseMatcher = CHINESE_NUMBER_PATTERN.matcher(latestUserMessage);
        if (chineseMatcher.find()) {
            return clampLimit(parseChineseNumber(chineseMatcher.group(1)));
        }
        return DEFAULT_HISTORY_LIMIT;
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private int normalizeQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            return 1;
        }
        return Math.min(quantity, 20);
    }

    private List<ToolItem> extractItemsFromMessage(String latestUserMessage) {
        if (!StringUtils.hasText(latestUserMessage)) {
            return Collections.emptyList();
        }

        String cleaned = latestUserMessage
                .replace("帮我", "")
                .replace("请", "")
                .replace("麻烦", "")
                .replace("下单", "")
                .replace("点单", "")
                .replace("给我", "")
                .replace("到默认地址", "")
                .replace("送到默认地址", "")
                .replace("用默认地址", "")
                .replace("其余信息你随便填", "")
                .replace("其余信息你自己填", "")
                .replace("其它信息你随便填", "")
                .trim();

        cleaned = PLACE_ORDER_SEGMENT_PATTERN.matcher(cleaned).replaceAll("").trim();
        cleaned = cleaned.replace("和", "、").replace(",", "、").replace("，", "、");
        String[] segments = cleaned.split("、");

        List<ToolItem> items = new ArrayList<>();
        for (String rawSegment : segments) {
            String segment = rawSegment == null ? "" : rawSegment.trim();
            if (!StringUtils.hasText(segment)) {
                continue;
            }

            int quantity = extractQuantity(segment);
            String itemName = segment
                    .replaceAll("(\\d+|[一二两三四五六七八九十]+)\\s*(份|个|瓶|杯|罐|听|碗|盒|套餐)?", "")
                    .replace("来", "")
                    .replace("一份", "")
                    .replace("一个", "")
                    .replace("一杯", "")
                    .replace("一瓶", "")
                    .replace("我要", "")
                    .trim();

            if (!StringUtils.hasText(itemName)) {
                continue;
            }

            ToolItem item = new ToolItem();
            item.setName(itemName);
            item.setQuantity(quantity);
            item.setKind(itemName.contains("套餐") ? "setmeal" : "dish");
            items.add(item);
        }
        return items;
    }

    private int extractQuantity(String segment) {
        Matcher matcher = QUANTITY_WITH_UNIT_PATTERN.matcher(segment);
        int quantity = 1;
        while (matcher.find()) {
            String value = matcher.group(1);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            if (value.matches("\\d+")) {
                quantity = Integer.parseInt(value);
            } else {
                quantity = parseChineseNumber(value);
            }
        }
        return normalizeQuantity(quantity);
    }

    private int parseChineseNumber(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 1;
        }
        if ("十".equals(raw)) {
            return 10;
        }
        if (raw.startsWith("十")) {
            return 10 + parseChineseDigit(raw.substring(1));
        }
        if (raw.endsWith("十")) {
            return parseChineseDigit(raw.substring(0, raw.length() - 1)) * 10;
        }
        if (raw.contains("十")) {
            String[] parts = raw.split("十");
            return parseChineseDigit(parts[0]) * 10 + parseChineseDigit(parts.length > 1 ? parts[1] : "");
        }
        return parseChineseDigit(raw);
    }

    private int parseChineseDigit(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 0;
        }
        switch (raw) {
            case "一":
                return 1;
            case "二":
            case "两":
                return 2;
            case "三":
                return 3;
            case "四":
                return 4;
            case "五":
                return 5;
            case "六":
                return 6;
            case "七":
                return 7;
            case "八":
                return 8;
            case "九":
                return 9;
            default:
                return 1;
        }
    }

    private String extractRemark(String latestUserMessage) {
        if (!StringUtils.hasText(latestUserMessage)) {
            return null;
        }
        if (latestUserMessage.contains("不要辣")) {
            return "不要辣";
        }
        if (latestUserMessage.contains("少辣")) {
            return "少辣";
        }
        if (latestUserMessage.contains("多辣")) {
            return "多辣";
        }
        if (latestUserMessage.contains("打包")) {
            return "请帮忙打包";
        }
        return null;
    }

    private boolean containsAny(String text, String... candidates) {
        if (!StringUtils.hasText(text) || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String formatOrderStatus(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case 1:
                return "待支付";
            case 2:
                return "待接单";
            case 3:
                return "已接单";
            case 4:
                return "配送中";
            case 5:
                return "已完成";
            case 6:
                return "已取消";
            default:
                return "状态" + status;
        }
    }

    private String cleanupExceptionMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "系统忙碌，请稍后再试。";
        }
        String cleaned = message.replaceAll("\\s+", " ").trim();
        if (cleaned.contains("SHOPPING_CART_IS_NULL")) {
            return "购物车为空，暂时无法完成下单。";
        }
        if (cleaned.contains("ADDRESS_BOOK_IS_NULL")) {
            return "默认地址不存在，请先设置默认地址。";
        }
        return cleaned;
    }

    private List<String> removeBlankValues(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("[\\s,，。！!？?；;：:]", "").toLowerCase(Locale.ROOT);
    }

    private void logBlock(String traceId, String label, String value) {
        log.info("[USER-AI:{}] {}:\n{}", traceId, label, StringUtils.hasText(value) ? value : "<empty>");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    @Data
    @AllArgsConstructor
    private static class ConversationMessage {
        private String role;
        private String content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ToolRequest {
        private String type;
        private String tool;
        private Integer limit;
        private Boolean useDefaultAddress;
        private String remark;
        private List<ToolItem> items;
        private String reason;

        static ToolRequest current(int limit, String reason) {
            ToolRequest request = new ToolRequest();
            request.setType("tool");
            request.setTool("query_current_orders");
            request.setLimit(limit);
            request.setReason(reason);
            return request;
        }

        static ToolRequest history(int limit, String reason) {
            ToolRequest request = new ToolRequest();
            request.setType("tool");
            request.setTool("query_history_orders");
            request.setLimit(limit);
            request.setReason(reason);
            return request;
        }

        static ToolRequest placeOrder(String reason) {
            ToolRequest request = new ToolRequest();
            request.setType("tool");
            request.setTool("place_order");
            request.setUseDefaultAddress(true);
            request.setReason(reason);
            return request;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ToolItem {
        private String name;
        private Integer quantity;
        private String kind;
        private String dishFlavor;
    }

    @Data
    @AllArgsConstructor
    private static class ResolvedOrderItem {
        private String name;
        private String kind;
        private Long dishId;
        private Long setmealId;
        private Integer quantity;
        private String dishFlavor;
        private BigDecimal price;
    }
}
