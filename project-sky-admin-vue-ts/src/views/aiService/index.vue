<template>
  <div class="ai-service-page">
    <div class="page-shell">
      <section class="hero-shell">
        <div class="hero-copy">
          <span class="eyebrow">SMART SERVICE WORKBENCH</span>
          <h1>把数据库查询结果直接放进对话。</h1>
          <p>
            现在不用再盯着旁边的 JSON 了。查询会自动整理成表格卡片，写入会自动整理成结果摘要，
            出错时也会优先说人话。
          </p>
        </div>

        <div class="hero-side">
          <div class="mode-card">
            <div class="mode-head">
              <div>
                <span class="mode-label">数据库权限</span>
                <p class="mode-text">
                  {{ allowWrite ? '当前已开启受控写库，可执行增删改查。' : '当前为只读模式，仅允许数据库查询。' }}
                </p>
              </div>
              <el-switch
                v-model="allowWrite"
                active-text="允许写库"
                inactive-text="只读模式"
              />
            </div>
          </div>

          <div class="quick-card">
            <span class="quick-label">快捷示例</span>
            <div class="quick-grid">
              <el-button
                v-for="example in quickExamples"
                :key="example"
                class="quick-button"
                plain
                @click="applyExample(example)"
              >
                {{ example }}
              </el-button>
            </div>
          </div>
        </div>
      </section>

      <div class="workspace-grid">
        <section class="conversation-panel">
          <div class="conversation-head">
            <div>
              <h2>对话区</h2>
              <p>查询结果、写入状态与错误提示都会直接显示在聊天流里。</p>
            </div>
            <div class="head-badges">
              <span class="head-badge">{{ allowWrite ? '写库已开启' : '当前只读' }}</span>
              <span class="head-badge">本轮消息 {{ messages.length }} 条</span>
            </div>
          </div>

          <div ref="messageList" class="message-stream">
            <article
              v-for="(item, index) in messages"
              :key="index"
              :class="['message-row', item.role, { 'is-thinking': item.pending }]"
            >
              <div class="message-avatar">{{ item.role === 'user' ? '你' : 'AI' }}</div>
              <div class="message-bubble">
                <div class="message-meta">{{ item.role === 'user' ? '你的指令' : '小威智能客服' }}</div>
                <div v-if="item.pending" class="thinking-content">
                  <span class="thinking-text">{{ item.content }}</span>
                  <span class="thinking-dots">
                    <i />
                    <i />
                    <i />
                  </span>
                </div>
                <div v-else class="message-content">{{ item.content }}</div>

                <div v-if="!item.pending && hasExecutionLogs(item)" class="message-results">
                  <section
                    v-for="(log, logIndex) in item.executionLogs || []"
                    :key="logIndex"
                    :class="['result-card', getResultCardClass(log)]"
                  >
                    <div class="result-head">
                      <div>
                        <h3>{{ getLogTitle(log) }}</h3>
                        <p>{{ getLogSummary(log) }}</p>
                      </div>
                      <span class="state-pill">{{ getLogState(log) }}</span>
                    </div>

                    <div v-if="isQueryLog(log)" class="result-table-shell">
                      <div class="table-caption">
                        <span>{{ getQueryCaption(log) }}</span>
                        <span v-if="isTruncated(log)" class="table-note">仅展示前 {{ getQueryRows(log).length }} 条</span>
                      </div>

                      <div v-if="getQueryRows(log).length" class="result-table-wrap">
                        <table class="result-table">
                          <thead>
                            <tr>
                              <th v-for="column in getQueryColumns(log)" :key="column">
                                {{ formatFieldLabel(column) }}
                              </th>
                            </tr>
                          </thead>
                          <tbody>
                            <tr v-for="(row, rowIndex) in getQueryRows(log)" :key="rowIndex">
                              <td v-for="column in getQueryColumns(log)" :key="column">
                                {{ formatFieldValue(column, row[column], log) }}
                              </td>
                            </tr>
                          </tbody>
                        </table>
                      </div>
                      <div v-else class="empty-result">本次查询没有返回记录。</div>
                    </div>

                    <div v-else-if="isWriteLog(log)" class="write-metrics">
                      <div v-for="metric in getMetricItems(log)" :key="metric.label" class="metric-card">
                        <span>{{ metric.label }}</span>
                        <strong>{{ metric.value }}</strong>
                      </div>
                    </div>

                    <div v-else class="error-box">
                      <p>{{ getFriendlyError(log) }}</p>
                      <span class="error-tip">{{ getErrorTip(log) }}</span>
                    </div>
                  </section>
                </div>
              </div>
            </article>
          </div>

          <div class="composer-shell">
          <div class="composer-hint">
            <span>Ctrl + Enter 发送</span>
            <span>{{ allowWrite ? '写库操作已允许' : '当前只允许查询' }}</span>
          </div>

          <div class="composer-input-wrap">
            <el-input
              v-model="inputMessage"
              type="textarea"
              :rows="4"
              resize="none"
              placeholder="例如：查询今天的订单数量；查看员工列表；新增员工时请直接给出姓名、账号、手机号等必要字段。"
              @keyup.ctrl.enter.native="sendMessage"
            />
            <el-button class="inline-send-button" type="primary" :loading="loading" @click="sendMessage">
              发送消息
            </el-button>
          </div>

          <div class="composer-footer">
            <p>写入类请求建议一次把关键字段说完整，这样客服更容易生成可执行的 SQL。</p>
          </div>
        </div>
      </section>

        <aside class="activity-panel">
          <div class="activity-head">
            <div>
              <h2>执行轨迹</h2>
              <p>保留本轮数据库调用记录，方便核对每一次 SQL 的执行状态。</p>
            </div>
          </div>

          <div v-if="executionLogs.length" class="activity-list">
            <div
              v-for="(log, index) in executionLogs"
              :key="index"
              :class="['activity-item', getResultCardClass(log)]"
            >
              <div class="activity-top">
                <strong>{{ getLogTitle(log) }}</strong>
                <span class="activity-state">{{ getLogState(log) }}</span>
              </div>
              <p>{{ getLogSummary(log) }}</p>
              <code>{{ log.sql }}</code>
            </div>
          </div>
          <div v-else class="activity-empty">
            还没有产生数据库执行记录。发起查询或写入操作后，这里会自动生成本轮审计轨迹。
          </div>
        </aside>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Component, Vue, Watch } from 'vue-property-decorator'
import { chatWithAiCustomerService } from '@/api/aiCustomerService'

interface IExecutionResult {
  type?: string
  success?: boolean
  rowCount?: number
  rows?: Array<{ [key: string]: any }>
  affectedRows?: number
  generatedKey?: number | string
  message?: string
  summary?: string
  operation?: string
  table?: string
  truncated?: boolean
}

interface IExecutionLog {
  tool?: string
  reason?: string
  sql?: string
  success?: boolean
  result?: IExecutionResult
}

interface IMessageItem {
  role: string
  content: string
  executionLogs?: IExecutionLog[]
  pending?: boolean
}

interface IAiServiceStorageState {
  allowWrite?: boolean
  messages?: IMessageItem[]
  executionLogs?: IExecutionLog[]
}

const AI_SERVICE_STORAGE_KEY = 'xiaowei-ai-service-state-v1'
const MAX_PERSISTED_MESSAGES = 80
const MAX_PERSISTED_LOGS = 20

const FIELD_LABEL_MAP: { [key: string]: string } = {
  order_count: '订单数量',
  total_count: '总数',
  total_orders: '订单数量',
  item_type: '绫诲瀷',
  item_name: '鍚嶇О',
  sales_count: '閿€閲?',
  sales_rank: '鎺掑悕',
  category_name: '鍒嗙被',
  price: '浠锋牸',
  id: '编号',
  name: '姓名',
  username: '账号',
  number: '订单号',
  phone: '手机号',
  status: '状态',
  sex: '性别',
  id_number: '身份证号',
  consignee: '收货人',
  address: '地址',
  order_time: '下单时间',
  checkout_time: '结账时间',
  pay_method: '支付方式',
  pay_status: '支付状态',
  remark: '备注',
  create_time: '创建时间',
  update_time: '更新时间',
  amount: '金额',
}

const TABLE_COLUMN_PRESETS: { [key: string]: string[] } = {
  orders: ['id', 'number', 'order_time', 'amount', 'status', 'phone', 'consignee', 'address'],
  employee: ['id', 'name', 'username', 'phone', 'sex', 'status', 'create_time'],
}

@Component({
  name: 'AiCustomerService',
})
export default class extends Vue {
  private inputMessage = ''
  private allowWrite = true
  private loading = false
  private executionLogs: IExecutionLog[] = []
  private quickExamples = [
    '查询今天的订单数量',
    '查看 employee 表前 5 条数据',
    '新增员工：姓名王磊，账号 wanglei001，手机号 13800001234，状态启用',
  ]
  private messages: IMessageItem[] = [
    {
      role: 'assistant',
      content:
        '你好，我是小威外卖智能客服。你可以直接问我业务问题，也可以让我查询数据库；如果你开启写库，我还能帮你执行受控的增删改查。',
    },
  ]

  created() {
    this.restoreConversationState()
  }

  mounted() {
    this.scrollToBottom()
  }

  beforeDestroy() {
    this.persistConversationState()
  }

  @Watch('allowWrite')
  private onAllowWriteChanged() {
    this.persistConversationState()
  }

  private async sendMessage() {
    const message = this.inputMessage.trim()
    if (!message || this.loading) {
      return
    }

    this.messages.push({
      role: 'user',
      content: message,
    })
    this.messages.push({
      role: 'assistant',
      content: '正在思考',
      pending: true,
    })
    this.inputMessage = ''
    this.loading = true
    this.persistConversationState()
    this.scrollToBottom()

    try {
      const payload = {
        message,
        allowWrite: this.allowWrite,
        history: this.messages
          .slice(0, this.messages.length - 1)
          .filter((item) => item.role === 'user' || item.role === 'assistant')
          .map((item) => ({
            role: item.role,
            content:
              item.role === 'assistant'
                ? this.normalizeAssistantContent(item.content, item.executionLogs || [])
                : item.content,
          })),
      }
      const { data } = await chatWithAiCustomerService(payload)
      if (data.code === 1) {
        const logs = this.normalizeExecutionLogs((data.data.executionLogs || []) as IExecutionLog[])
        const answer = this.normalizeAssistantContent(String(data.data.answer || ''), logs)
        this.replacePendingAssistantMessage({
          role: 'assistant',
          content: answer,
          executionLogs: logs,
        })
        this.executionLogs = logs
        this.persistConversationState()
      } else {
        this.replacePendingAssistantMessage({
          role: 'assistant',
          content: data.msg || '智能客服暂时没有返回有效结果，请稍后再试。',
        })
        this.persistConversationState()
        this.$message.error(data.msg || '智能客服暂时没有返回有效结果，请稍后再试。')
      }
    } catch (error) {
      this.replacePendingAssistantMessage({
        role: 'assistant',
        content: '智能客服请求失败，请确认后端服务和 Ollama 服务都已启动。',
      })
      this.persistConversationState()
      this.$message.error('智能客服请求失败，请确认后端服务和 Ollama 服务都已启动。')
    } finally {
      this.loading = false
      this.scrollToBottom()
    }
  }

  private getConversationStorage(): Storage | null {
    if (typeof window === 'undefined') {
      return null
    }
    return window.localStorage
  }

  private restoreConversationState() {
    const storage = this.getConversationStorage()
    if (!storage) {
      return
    }
    const rawState = storage.getItem(AI_SERVICE_STORAGE_KEY)
    if (!rawState) {
      return
    }
    try {
      const parsedState = JSON.parse(rawState) as IAiServiceStorageState
      if (typeof parsedState.allowWrite === 'boolean') {
        this.allowWrite = parsedState.allowWrite
      }
      const restoredMessages = this.normalizeStoredMessages(parsedState.messages)
      if (restoredMessages.length) {
        this.messages = restoredMessages
      }
      const restoredLogs = this.normalizeExecutionLogs((parsedState.executionLogs || []) as IExecutionLog[])
      this.executionLogs = restoredLogs
    } catch (error) {
      storage.removeItem(AI_SERVICE_STORAGE_KEY)
    }
  }

  private persistConversationState() {
    const storage = this.getConversationStorage()
    if (!storage) {
      return
    }
    try {
      const messages = this.messages
        .filter((item) => !item.pending)
        .slice(-MAX_PERSISTED_MESSAGES)
        .map((item) => {
          const normalizedItem: IMessageItem = {
            role: item.role,
            content:
              item.role === 'assistant'
                ? this.normalizeAssistantContent(item.content, item.executionLogs || [])
                : String(item.content || '').trim(),
          }
          const logs = this.normalizeExecutionLogs((item.executionLogs || []) as IExecutionLog[]).slice(
            -MAX_PERSISTED_LOGS
          )
          if (logs.length) {
            normalizedItem.executionLogs = logs
          }
          return normalizedItem
        })
      const state: IAiServiceStorageState = {
        allowWrite: this.allowWrite,
        messages,
        executionLogs: this.normalizeExecutionLogs(this.executionLogs).slice(-MAX_PERSISTED_LOGS),
      }
      storage.setItem(AI_SERVICE_STORAGE_KEY, JSON.stringify(state))
    } catch (error) {
      // Ignore persistence failures such as localStorage quota limits.
    }
  }

  private normalizeStoredMessages(messages: IMessageItem[] | undefined) {
    if (!Array.isArray(messages) || !messages.length) {
      return []
    }
    const normalizedMessages: IMessageItem[] = []
    messages.forEach((item) => {
      if (!item || typeof item.content !== 'string' || item.pending) {
        return
      }
      const role = item.role === 'user' ? 'user' : 'assistant'
      const logs = role === 'assistant'
        ? this.normalizeExecutionLogs((item.executionLogs || []) as IExecutionLog[]).slice(-MAX_PERSISTED_LOGS)
        : []
      const content = role === 'assistant'
        ? this.normalizeAssistantContent(item.content, logs)
        : item.content.trim()
      if (!content) {
        return
      }
      const normalizedItem: IMessageItem = {
        role,
        content,
      }
      if (logs.length) {
        normalizedItem.executionLogs = logs
      }
      normalizedMessages.push(normalizedItem)
    })
    return normalizedMessages.slice(-MAX_PERSISTED_MESSAGES)
  }

  private replacePendingAssistantMessage(message: IMessageItem) {
    const pendingIndex = this.messages.findIndex((item) => item.pending)
    const nextMessage: IMessageItem = {
      ...message,
      pending: false,
    }
    if (pendingIndex === -1) {
      this.messages.push(nextMessage)
      return
    }
    this.$set(this.messages, pendingIndex, nextMessage)
  }

  private hasExecutionLogs(item: IMessageItem) {
    return !!(item.executionLogs && item.executionLogs.length)
  }

  private normalizeExecutionLogs(logs: IExecutionLog[]) {
    const deduplicatedLogs: IExecutionLog[] = []
    const seenKeys = new Set<string>()
    logs.forEach((log) => {
      const result = (log && log.result) || {}
      const key = JSON.stringify({
        sql: log.sql || '',
        success: log.success === true,
        type: result.type || '',
        table: result.table || '',
        rowCount: result.rowCount || 0,
        affectedRows: result.affectedRows || 0,
        summary: result.summary || '',
      })
      if (seenKeys.has(key)) {
        return
      }
      seenKeys.add(key)
      deduplicatedLogs.push(log)
    })
    return deduplicatedLogs
  }

  private normalizeAssistantContent(content: string, logs: IExecutionLog[] = []) {
    const trimmedContent = (content || '').trim()
    if (!trimmedContent) {
      return ''
    }
    const payload = this.tryParseAssistantPayload(trimmedContent)
    if (!payload) {
      return trimmedContent
    }
    const type = String(payload.type || '').toLowerCase()
    if ((type === 'response' || type === 'message') && typeof payload.content === 'string' && payload.content.trim()) {
      return payload.content.trim()
    }
    if (type === 'tool') {
      const summary = logs[0] && logs[0].result && logs[0].result.summary
      if (summary) {
        return String(summary)
      }
      if (typeof payload.reason === 'string' && payload.reason.trim()) {
        return payload.reason.trim()
      }
      return '已生成数据库操作请求。'
    }
    return trimmedContent
  }

  private tryParseAssistantPayload(content: string): { [key: string]: any } | null {
    try {
      const payload = JSON.parse(content)
      return payload && typeof payload === 'object' ? payload : null
    } catch (error) {
      return null
    }
  }

  private applyExample(example: string) {
    this.inputMessage = example
  }

  private getResult(log: IExecutionLog): IExecutionResult {
    return (log && log.result) || {}
  }

  private isQueryLog(log: IExecutionLog) {
    return this.getResult(log).type === 'query'
  }

  private isWriteLog(log: IExecutionLog) {
    return this.getResult(log).type === 'write'
  }

  private isTruncated(log: IExecutionLog) {
    return this.getResult(log).truncated === true
  }

  private getLogState(log: IExecutionLog) {
    if (log.success === false) {
      return '执行失败'
    }
    if (this.isQueryLog(log)) {
      return '查询完成'
    }
    if (this.isWriteLog(log)) {
      return '写入完成'
    }
    return '已执行'
  }

  private getLogTitle(log: IExecutionLog) {
    const result = this.getResult(log)
    const operation = result.operation || ''
    const table = this.formatTableName(result.table || '')
    if (log.success === false) {
      return table ? `${table}操作失败` : '数据库操作失败'
    }
    if (operation === 'select') {
      return table ? `${table}查询结果` : '查询结果'
    }
    if (operation === 'insert') {
      return table ? `已新增${table}` : '新增成功'
    }
    if (operation === 'update') {
      return table ? `已更新${table}` : '更新成功'
    }
    if (operation === 'delete') {
      return table ? `已删除${table}` : '删除成功'
    }
    return log.reason || '数据库工具调用'
  }

  private getLogSummary(log: IExecutionLog) {
    const result = this.getResult(log)
    if (result.summary) {
      return result.summary
    }
    if (log.success === false) {
      return this.getFriendlyError(log)
    }
    return log.reason || '数据库工具调用'
  }

  private getQueryCaption(log: IExecutionLog) {
    const result = this.getResult(log)
    const rowCount = result.rowCount || 0
    return `共返回 ${rowCount} 条记录`
  }

  private getQueryRows(log: IExecutionLog) {
    const rows = this.getResult(log).rows
    return Array.isArray(rows) ? rows : []
  }

  private getQueryColumns(log: IExecutionLog) {
    const rows = this.getQueryRows(log)
    if (!rows.length) {
      return []
    }
    const tableName = String(this.getResult(log).table || '').toLowerCase()
    const presetColumns = TABLE_COLUMN_PRESETS[tableName]
    if (presetColumns && presetColumns.length) {
      const matchedColumns = presetColumns.filter((column) =>
        rows.some((row) => Object.prototype.hasOwnProperty.call(row, column))
      )
      if (matchedColumns.length) {
        return matchedColumns
      }
    }
    return Object.keys(rows[0])
  }

  private getMetricItems(log: IExecutionLog) {
    const result = this.getResult(log)
    const metrics: Array<{ label: string; value: string | number }> = []
    metrics.push({
      label: '动作',
      value: this.getOperationLabel(result.operation || ''),
    })
    if (result.table) {
      metrics.push({
        label: '数据表',
        value: this.formatTableName(result.table),
      })
    }
    if (result.affectedRows !== undefined) {
      metrics.push({
        label: '影响记录',
        value: result.affectedRows,
      })
    }
    if (result.generatedKey !== undefined) {
      metrics.push({
        label: '新记录编号',
        value: result.generatedKey,
      })
    }
    return metrics
  }

  private getOperationLabel(operation: string) {
    if (operation === 'select') {
      return '查询'
    }
    if (operation === 'insert') {
      return '新增'
    }
    if (operation === 'update') {
      return '更新'
    }
    if (operation === 'delete') {
      return '删除'
    }
    return '执行'
  }

  private getFriendlyError(log: IExecutionLog) {
    const result = this.getResult(log)
    const message = String(result.message || '数据库执行失败')
    const duplicate = /Duplicate entry '([^']+)' for key '([^']+)'/i.exec(message)
    if (duplicate) {
      const duplicateValue = duplicate[1]
      const duplicateKey = duplicate[2].toLowerCase()
      if (duplicateKey.indexOf('username') > -1) {
        return `新增失败：账号“${duplicateValue}”已存在，请换一个唯一账号。`
      }
      if (duplicateKey.indexOf('phone') > -1) {
        return `新增失败：手机号“${duplicateValue}”已存在，请换一个未使用的手机号。`
      }
      return `新增失败：字段值“${duplicateValue}”与唯一约束冲突，请调整后重试。`
    }
    if (message.indexOf('当前为只读模式') > -1) {
      return '当前仍处于只读模式，请先打开写库开关，再执行新增、修改或删除。'
    }
    return message
  }

  private getErrorTip(log: IExecutionLog) {
    const message = this.getFriendlyError(log)
    if (message.indexOf('账号') > -1 && message.indexOf('已存在') > -1) {
      return '建议重新提供一个唯一账号，例如 `wanglei002` 后再次发送。'
    }
    if (message.indexOf('只读模式') > -1) {
      return '打开上方“允许写库”开关后，再重新发送同一条指令即可。'
    }
    return '如果你愿意，也可以把缺少的字段补充完整后让我重新执行。'
  }

  private getResultCardClass(log: IExecutionLog) {
    if (log.success === false) {
      return 'is-error'
    }
    if (this.isWriteLog(log)) {
      return 'is-write'
    }
    return 'is-query'
  }

  private formatTableName(tableName: string) {
    if (!tableName) {
      return ''
    }
    const normalized = tableName.toLowerCase()
    if (normalized === 'employee') {
      return '员工'
    }
    if (normalized === 'orders') {
      return '订单'
    }
    if (normalized === 'order_detail') {
      return '订单明细'
    }
    if (normalized === 'dish') {
      return '菜品'
    }
    if (normalized === 'category') {
      return '分类'
    }
    if (normalized === 'setmeal') {
      return '套餐'
    }
    if (normalized === 'shopping_cart') {
      return '购物车'
    }
    if (normalized === 'address_book') {
      return '地址簿'
    }
    if (normalized === 'user') {
      return '用户'
    }
    return tableName
  }

  private formatFieldLabel(field: string) {
    return FIELD_LABEL_MAP[field] || field
  }

  private formatFieldValue(field: string, value: any, log?: IExecutionLog) {
    if (value === null || value === undefined || value === '') {
      return '-'
    }
    const tableName = log ? String(this.getResult(log).table || '').toLowerCase() : ''
    if (field === 'item_type') {
      if (String(value).toLowerCase() === 'dish') {
        return '菜品'
      }
      if (String(value).toLowerCase() === 'setmeal') {
        return '套餐'
      }
    }
    if (field === 'status') {
      if (tableName === 'orders') {
        if (String(value) === '1') {
          return '待付款'
        }
        if (String(value) === '2') {
          return '待接单'
        }
        if (String(value) === '3') {
          return '已接单'
        }
        if (String(value) === '4') {
          return '派送中'
        }
        if (String(value) === '5') {
          return '已完成'
        }
        if (String(value) === '6') {
          return '已取消'
        }
      }
      if (String(value) === '1') {
        return '启用'
      }
      if (String(value) === '0' || String(value) === '2') {
        return '禁用'
      }
    }
    if (field === 'pay_method') {
      if (String(value) === '1') {
        return '微信'
      }
      if (String(value) === '2') {
        return '支付宝'
      }
    }
    if (field === 'pay_status') {
      if (String(value) === '1') {
        return '已支付'
      }
      if (String(value) === '0') {
        return '未支付'
      }
    }
    if (field === 'sex') {
      if (String(value) === '1') {
        return '男'
      }
      if (String(value) === '0') {
        return '女'
      }
    }
    const text = String(value)
    return text.length > 36 ? `${text.slice(0, 33)}...` : text
  }

  private scrollToBottom() {
    this.$nextTick(() => {
      const messageList = this.$refs.messageList as HTMLElement
      if (messageList) {
        messageList.scrollTop = messageList.scrollHeight
      }
    })
  }
}
</script>

<style lang="scss" scoped>
.ai-service-page {
  --workspace-panel-height: clamp(520px, calc(100vh - 260px), 780px);
  min-height: calc(100vh - 74px);
  padding: 18px;
  background:
    radial-gradient(circle at 8% 4%, rgba(255, 188, 82, 0.28), transparent 18%),
    radial-gradient(circle at 92% 16%, rgba(62, 122, 255, 0.12), transparent 20%),
    linear-gradient(180deg, #faf6ee 0%, #f1f4fb 52%, #edf2f8 100%);
}

.page-shell {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-width: 1600px;
  margin: 0 auto;
}

.hero-shell {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-radius: 22px;
  color: #fff;
  background: linear-gradient(140deg, #171e2d, #273247 58%, #33415b 100%);
  box-shadow: 0 30px 70px rgba(17, 26, 46, 0.18);
}

.eyebrow {
  display: inline-flex;
  margin-bottom: 12px;
  font-size: 11px;
  letter-spacing: 0.28em;
  color: rgba(255, 255, 255, 0.62);
}

.hero-copy {
  display: none;

  h1 {
    margin: 0;
    max-width: 540px;
    font-size: 31px;
    line-height: 1.18;
  }

  p {
    max-width: 560px;
    margin: 12px 0 0;
    font-size: 14px;
    line-height: 1.7;
    color: rgba(255, 255, 255, 0.74);
  }
}

.hero-side {
  display: grid;
  width: min(100%, 860px);
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
}

.mode-card,
.quick-card {
  padding: 16px 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.mode-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.mode-head > div:first-child {
  min-width: 0;
  flex: 1;
}

.mode-head .el-switch {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}

.mode-head .el-switch__label {
  white-space: nowrap;
}

.mode-head .el-switch__label * {
  white-space: nowrap;
}

.mode-head .el-switch__core {
  margin: 0 8px;
}

.mode-label,
.quick-label {
  display: inline-flex;
  margin-bottom: 8px;
  font-size: 11px;
  letter-spacing: 0.18em;
  color: rgba(255, 255, 255, 0.58);
  text-transform: uppercase;
}

.mode-text {
  margin: 0;
  line-height: 1.65;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.78);
}

.quick-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.quick-button {
  border-radius: 999px;
  border-color: rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-size: 13px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 4.4fr) 240px;
  gap: 14px;
  margin-top: 14px;
  align-items: stretch;
}

.conversation-panel,
.activity-panel {
  display: flex;
  flex-direction: column;
  height: var(--workspace-panel-height);
  min-height: var(--workspace-panel-height);
  overflow: hidden;
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.65);
  background: rgba(255, 252, 246, 0.78);
  backdrop-filter: blur(20px);
  box-shadow: 0 24px 60px rgba(19, 28, 45, 0.08);
}

.conversation-head,
.activity-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18px;
  padding: 20px 22px 16px;
  border-bottom: 1px solid rgba(202, 210, 223, 0.55);

  h2 {
    margin: 0;
    font-size: 24px;
    color: #172033;
  }

  p {
    margin: 6px 0 0;
    line-height: 1.65;
    font-size: 14px;
    color: #6c7486;
  }
}

.head-badges {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.head-badge {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  background: #fff3d6;
  color: #8b5b00;
  font-size: 12px;
  font-weight: 700;
}

.message-stream {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  gap: 14px;
  padding: 20px 22px 6px;
  overflow-y: auto;
}

.message-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.message-row.user {
  flex-direction: row-reverse;
}

.message-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 16px;
  background: linear-gradient(135deg, #ffbf3a, #ff8d2d);
  color: #172033;
  font-size: 14px;
  font-weight: 800;
  flex: 0 0 auto;
}

.message-row.user .message-avatar {
  background: linear-gradient(135deg, #4aa0ff, #2e72ef);
  color: #fff;
}

.message-bubble {
  width: 100%;
  padding: 16px 18px;
  border-radius: 22px;
  box-shadow: 0 18px 40px rgba(19, 28, 45, 0.06);
}

.message-row.assistant .message-bubble {
  background: linear-gradient(180deg, #fffef9, #fff4de);
}

.message-row.user .message-bubble {
  width: min(84%, 960px);
  background: linear-gradient(135deg, #489cff, #2f73f0);
  color: #fff;
}

.message-meta {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: rgba(30, 38, 52, 0.52);
}

.message-row.user .message-meta {
  color: rgba(255, 255, 255, 0.75);
}

.message-content {
  margin-top: 8px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 15px;
}

.thinking-content {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  color: #43506a;
}

.message-row.user .thinking-content {
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.92);
}

.thinking-text {
  font-size: 14px;
  font-weight: 600;
}

.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.thinking-dots i {
  display: block;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: currentColor;
  opacity: 0.28;
  animation: thinking-bounce 1.2s ease-in-out infinite;
}

.thinking-dots i:nth-child(2) {
  animation-delay: 0.16s;
}

.thinking-dots i:nth-child(3) {
  animation-delay: 0.32s;
}

.message-results {
  margin-top: 14px;
  display: grid;
  gap: 12px;
}

.result-card {
  padding: 16px;
  border-radius: 18px;
  border: 1px solid rgba(205, 214, 226, 0.74);
  background: rgba(255, 255, 255, 0.92);
}

.result-card.is-query {
  border-color: rgba(105, 138, 255, 0.22);
}

.result-card.is-write {
  border-color: rgba(255, 184, 61, 0.34);
}

.result-card.is-error {
  border-color: rgba(244, 96, 96, 0.28);
  background: #fff6f6;
}

.result-head {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;

  h3 {
    margin: 0;
    font-size: 16px;
    color: #172033;
  }

  p {
    margin: 6px 0 0;
    line-height: 1.7;
    font-size: 14px;
    color: #5c667a;
  }
}

.state-pill {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  background: #edf3ff;
  color: #2b62d9;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.is-write .state-pill {
  background: #fff2da;
  color: #995b00;
}

.is-error .state-pill {
  background: #ffe4e4;
  color: #d84e4e;
}

.result-table-shell {
  margin-top: 14px;
}

.table-caption {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: #667086;
  font-size: 12px;
}

.table-note {
  color: #b56c00;
}

.result-table-wrap {
  overflow-x: auto;
  border-radius: 18px;
  border: 1px solid #ebedf4;
}

.result-table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;

  th,
  td {
    padding: 10px 12px;
    text-align: left;
    border-bottom: 1px solid #eef1f6;
    font-size: 12px;
    color: #263042;
  }

  th {
    background: #f7f8fc;
    color: #667086;
    font-weight: 700;
  }

  tr:last-child td {
    border-bottom: none;
  }
}

.write-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.metric-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 16px;
  background: #fff8ea;

  span {
    font-size: 12px;
    color: #7a6040;
  }

  strong {
    font-size: 18px;
    color: #172033;
    word-break: break-word;
  }
}

.error-box {
  margin-top: 14px;
  padding: 14px 16px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid rgba(236, 167, 167, 0.55);

  p {
    margin: 0;
    line-height: 1.8;
    color: #9f2e2e;
  }
}

.error-tip {
  display: inline-block;
  margin-top: 10px;
  color: #7f8796;
  font-size: 12px;
}

.empty-result {
  padding: 14px;
  border-radius: 16px;
  background: #fff;
  color: #778093;
  font-size: 12px;
}

.composer-shell {
  flex: 0 0 auto;
  padding: 16px 22px 20px;
  border-top: 1px solid rgba(202, 210, 223, 0.55);
  background: rgba(255, 252, 246, 0.94);
}

.composer-input-wrap {
  position: relative;
}

.composer-hint {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  color: #7c8494;
  font-size: 12px;
}

.composer-footer {
  display: flex;
  justify-content: flex-start;
  gap: 14px;
  align-items: center;
  margin-top: 12px;

  p {
    margin: 0;
    color: #6c7486;
    line-height: 1.65;
    font-size: 13px;
  }
}

.activity-list {
  display: grid;
  flex: 1;
  min-height: 0;
  gap: 12px;
  padding: 18px;
  overflow-y: auto;
}

.activity-item {
  padding: 14px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(212, 218, 230, 0.8);

  p {
    margin: 8px 0 0;
    color: #5d6679;
    line-height: 1.7;
    font-size: 13px;
  }

  code {
    display: block;
    margin-top: 10px;
    padding: 10px 12px;
    border-radius: 14px;
    background: #172033;
    color: #edf2ff;
    font-size: 12px;
    line-height: 1.7;
    word-break: break-all;
  }
}

.activity-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;

  strong {
    color: #172033;
    font-size: 15px;
  }
}

.activity-state {
  color: #6d7688;
  font-size: 12px;
  font-weight: 700;
}

.activity-empty {
  padding: 20px 18px;
  color: #6d7688;
  line-height: 1.7;
  font-size: 14px;
}

::v-deep .el-switch__label {
  color: rgba(255, 255, 255, 0.78);
}

::v-deep .el-switch__label.is-active {
  color: #ffd27d;
}

::v-deep .el-switch__core {
  border-color: rgba(255, 255, 255, 0.24) !important;
  background: rgba(255, 255, 255, 0.16) !important;
}

::v-deep .el-switch.is-checked .el-switch__core {
  border-color: #ffb53f !important;
  background: #ffb53f !important;
}

::v-deep .composer-shell .el-textarea__inner {
  min-height: 112px !important;
  border: 1px solid #dde3ef;
  border-radius: 18px;
  padding: 14px 16px;
  line-height: 1.75;
  font-size: 14px;
  background: rgba(255, 255, 255, 0.94);
}

::v-deep .composer-input-wrap .el-textarea__inner {
  padding-right: 148px;
  padding-bottom: 66px;
}

::v-deep .composer-shell .el-textarea__inner:focus {
  border-color: #ffb74c;
  box-shadow: 0 0 0 4px rgba(255, 196, 104, 0.16);
}

.inline-send-button {
  position: absolute;
  right: 14px;
  bottom: 14px;
  min-width: 116px;
  height: 44px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(135deg, #ffbf3a, #ff8d2d) !important;
  color: #172033 !important;
  font-weight: 700;
  box-shadow: 0 12px 24px rgba(255, 167, 51, 0.22);
}

@keyframes thinking-bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.28;
  }
  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

@media (max-width: 1380px) {
  .ai-service-page {
    --workspace-panel-height: auto;
  }

  .workspace-grid {
    grid-template-columns: 1fr;
  }

  .conversation-panel,
  .activity-panel {
    height: auto;
    min-height: 0;
  }

  .message-stream {
    max-height: 460px;
  }

  .activity-list {
    max-height: none;
  }
}

@media (max-width: 1080px) {
  .hero-shell {
    justify-content: stretch;
  }

  .hero-side {
    width: 100%;
    grid-template-columns: 1fr;
  }

  .conversation-head,
  .activity-head,
  .composer-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 720px) {
  .ai-service-page {
    padding: 16px;
  }

  .hero-shell,
  .conversation-panel,
  .activity-panel {
    border-radius: 24px;
  }

  .message-row,
  .message-row.user {
    flex-direction: column;
  }

  .message-bubble {
    width: 100%;
  }

  ::v-deep .composer-input-wrap .el-textarea__inner {
    padding-right: 16px;
    padding-bottom: 72px;
  }

  .inline-send-button {
    left: 14px;
    right: 14px;
    min-width: 0;
  }
}
</style>
