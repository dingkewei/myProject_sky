# GitHub 上传指南

这份文档是给你上传仓库时直接用的，重点是把这个项目包装成“传统外卖系统 + AI 业务增强”的毕业设计作品，而不是一个普通 CRUD 项目。

## 1. 仓库命名建议

推荐优先使用下面这类名字：

- `xiaowei-takeout-ai`
- `ai-enhanced-takeout-system`
- `smart-takeout-graduation-project`
- `sky-take-out-ai-upgrade`

如果你想更偏中文答辩语境，也可以在 README 标题中写：

- `小微外卖 AI 增强版`
- `基于本地大模型的智能外卖系统`

## 2. GitHub About 描述

### 中文版

一个基于 Spring Boot + Vue2 的外卖系统毕业设计项目，在传统订单、菜品、套餐、员工与数据统计模块基础上，引入本地大模型能力，实现商家端自然语言查库/受控写库，以及用户端智能客服与自然语言下单。

### 英文版

An AI-enhanced takeout system built with Spring Boot and Vue2. Beyond traditional order, dish, set meal, employee, and analytics modules, it integrates a local LLM for merchant-side natural language database operations and user-side conversational order assistance.

## 3. 建议放在 README 开头的项目卖点

你可以突出下面这句话：

> 本项目的创新点不在于“接入了大模型”，而在于让 AI 从信息问答层进入业务执行层，在受控约束下参与真实外卖系统的数据查询、业务操作和用户下单流程。

## 4. 推荐 GitHub Topics

建议在仓库 Topics 中添加这些标签：

- `spring-boot`
- `vue2`
- `typescript`
- `mybatis`
- `mysql`
- `redis`
- `websocket`
- `ollama`
- `llm`
- `ai-customer-service`
- `ai-agent`
- `takeout-system`
- `graduation-project`

## 5. 上传前必须处理的内容

### 敏感信息

你当前项目里已经发现了以下高风险内容：

- MySQL 用户名和密码
- 阿里云 OSS `access-key-id` / `access-key-secret`
- 微信 `appid` / `secret`

这些信息现在位于：

- [application-dev.yml](../sky-take-out/sky-server/src/main/resources/application-dev.yml)

建议做法：

- GitHub 中不要提交真实 `application-dev.yml`
- 保留一个可公开的示例配置文件
- 本地真实配置只放在未跟踪文件里

### 非核心目录

以下目录建议不要作为毕业设计主仓库公开：

- `thesis_tools/`
- `translation_tools/`
- `.hyperion/`
- `.codex-tmp/`
- `node_modules/`
- `dist/`
- `.idea/`
- 日志文件

### 嵌套 Git 仓库

`sky-take-out/` 目录里目前还有一个独立 `.git`。

如果你想把整个 `project_01` 作为一个完整仓库上传，推荐你二选一：

1. 把根目录作为唯一仓库，处理掉 `sky-take-out` 内部的独立 Git 历史。
2. 分成两个仓库，分别上传后端和前端。

如果你是为了展示毕业设计成果，通常更推荐第 1 种，也就是前后端放进同一个仓库，更利于老师和面试官直接浏览。

## 6. 最值得强调的 AI 亮点

你在介绍项目时，不要只说“接入了 DeepSeek”或“用了 Ollama”，建议从业务价值说：

### 亮点一：商家端自然语言查库和受控写库

- 商家不需要手写 SQL，就可以通过自然语言获取订单、员工、菜品等业务数据
- 系统通过 schema 上下文约束模型输出
- 只允许单条 SQL，禁止危险操作
- 前端会展示结果摘要、结构化表格和执行轨迹

### 亮点二：用户端智能客服与智能下单

- AI 不只是回答用户问题，而是能把自然语言解析为实际下单动作
- 可识别商品名、数量、备注、默认地址等业务信息
- 能查询当前订单和历史订单，具备真实业务闭环

### 亮点三：AI 调用过程可控、可解释

- 有读写权限开关
- 有 SQL 校验
- 有脱敏处理
- 有执行日志

这一点很适合写进答辩 PPT，因为它能把你的项目和“普通接聊天接口”的作品拉开差距。

## 7. 适合答辩或 README 的创新点表达

你可以直接使用下面这段话：

> 本项目在传统外卖系统基础上，设计并实现了一个面向真实业务流程的 AI 增强方案。与只提供对话问答的系统不同，本项目通过本地大模型、受控工具调用和业务规则约束，使 AI 能够参与商家端的数据查询与受控操作，以及用户端的订单查询与自然语言下单，从而提升系统的交互智能性与业务自动化水平。

## 8. 推荐的仓库展示结构

建议你的 GitHub 仓库首页重点呈现以下内容：

1. 项目简介
2. AI 创新点
3. 系统架构图
4. 技术栈
5. 功能模块
6. 快速启动
7. 演示截图或录屏
8. 已知限制与后续优化方向

## 9. 录屏演示顺序建议

如果你准备录一个 2 到 4 分钟的 GitHub 演示视频，推荐顺序如下：

1. 展示工作台、订单管理、菜品管理、统计分析，证明这是一个完整外卖系统。
2. 进入 AI 页面，演示“查询今天订单数量”。
3. 演示“查看 employee 表前 5 条数据”。
4. 打开写库权限，演示“新增员工”。
5. 展示执行结果卡片和执行轨迹。
6. 补充说明用户端还支持查询订单与自然语言下单。

## 10. 开源许可建议

如果你只是作品展示，暂时不想开放商用和二次分发，可以先不放宽松开源协议，或在 README 中明确“仅用于学习与交流”。

如果你愿意开放学习用途，推荐：

- `MIT`
- `Apache-2.0`

如果你不确定，就先不要随便添加 License 文件，避免授权范围和你的真实意图不一致。
