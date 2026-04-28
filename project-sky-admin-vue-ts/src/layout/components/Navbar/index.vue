<template>
  <div class="navbar">
    <div class="nav-left">
      <div class="nav-toggle">
        <hamburger
          id="hamburger-container"
          :is-active="sidebar.opened"
          class="hamburger-container"
          @toggleClick="toggleSideBar"
        />
      </div>

      <div class="nav-brand">
        <span class="nav-kicker">XIAOWEI DELIVERY DASHBOARD</span>
        <div class="nav-title-row">
          <h1>小威外卖管理台</h1>
          <span :class="['business-pill', status === 1 ? 'is-open' : 'is-closed']">
            {{ status === 1 ? '营业中' : '打烊中' }}
          </span>
        </div>
      </div>
    </div>

    <div :key="restKey" class="right-menu">
      <audio ref="audioVo" hidden>
        <source src="./../../../assets/preview.mp3" type="audio/mp3" />
      </audio>
      <audio ref="audioVo2" hidden>
        <source src="./../../../assets/reminder.mp3" type="audio/mp3" />
      </audio>

      <button class="nav-action" type="button" @click="handleStatus">
        <i class="el-icon-time" />
        <span>营业状态设置</span>
      </button>

      <div ref="profileMenu" class="avatar-wrapper">
        <button
          type="button"
          :class="['profile-button', { active: shopShow }]"
          @click.stop="toggleProfileMenu"
        >
          <span>{{ name }}</span>
          <i class="el-icon-arrow-down profile-arrow" />
        </button>

        <div v-if="shopShow" class="userList" @click.stop>
          <p class="menu-item" @click="handlePwd">
            <span>修改密码</span>
            <i class="el-icon-lock" />
          </p>
          <p class="menu-item" @click="logout">
            <span>退出登录</span>
            <i class="el-icon-switch-button" />
          </p>
        </div>
      </div>
    </div>

    <el-dialog
      title="营业状态设置"
      :visible.sync="dialogVisible"
      width="420px"
      :show-close="false"
      :append-to-body="true"
      custom-class="business-status-dialog"
    >
      <el-radio-group v-model="setStatus">
        <el-radio :label="1">
          营业中
          <span>当前餐厅处于营业状态，会自动接收新的即时订单。</span>
        </el-radio>
        <el-radio :label="0">
          打烊中
          <span>当前餐厅处于打烊状态，暂停接收新的即时订单。</span>
        </el-radio>
      </el-radio-group>
      <span slot="footer" class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">确定</el-button>
      </span>
    </el-dialog>

    <Password :dialog-form-visible="dialogFormVisible" @handleclose="handlePwdClose" />
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-property-decorator'
import { AppModule } from '@/store/modules/app'
import { UserModule } from '@/store/modules/user'
import Hamburger from '@/components/Hamburger/index.vue'
import { getStatus, setStatus } from '@/api/users'
import Cookies from 'js-cookie'
import Password from '../components/password.vue'

@Component({
  name: 'Navbar',
  components: {
    Hamburger,
    Password,
  },
})
export default class extends Vue {
  private restKey = 0
  private websocket: WebSocket | null = null
  private shopShow = false
  private dialogVisible = false
  private status = 1
  private setStatus = 1
  private dialogFormVisible = false

  get sidebar() {
    return AppModule.sidebar
  }

  get name() {
    return (UserModule.userInfo as any).name
      ? (UserModule.userInfo as any).name
      : JSON.parse(Cookies.get('user_info') as any).name
  }

  mounted() {
    this.getStatus()
    document.addEventListener('click', this.handleDocumentClick)
  }

  created() {
    this.webSocket()
  }

  beforeDestroy() {
    if (this.websocket) {
      this.websocket.close()
    }
    document.removeEventListener('click', this.handleDocumentClick)
  }

  private webSocket() {
    const vm = this as any
    const clientId = Math.random().toString(36).substr(2)
    const socketUrl = process.env.VUE_APP_SOCKET_URL + clientId

    if (typeof WebSocket === 'undefined') {
      vm.$notify({
        title: '提示',
        message: '当前浏览器无法接收实时提醒信息，请使用现代浏览器重试。',
        type: 'warning',
        duration: 0,
      })
      return
    }

    this.websocket = new WebSocket(socketUrl)

    this.websocket.onopen = () => {
      console.log('浏览器 WebSocket 已打开')
    }

    this.websocket.onmessage = (msg) => {
      const audioVo = this.$refs.audioVo as HTMLAudioElement
      const audioVo2 = this.$refs.audioVo2 as HTMLAudioElement
      if (audioVo) {
        audioVo.currentTime = 0
      }
      if (audioVo2) {
        audioVo2.currentTime = 0
      }

      const jsonMsg = JSON.parse(msg.data)
      if (jsonMsg.type === 1 && audioVo) {
        audioVo.play()
      } else if (jsonMsg.type === 2 && audioVo2) {
        audioVo2.play()
      }

      vm.$notify({
        title: jsonMsg.type === 1 ? '待接单提醒' : '催单提醒',
        duration: 0,
        dangerouslyUseHTMLString: true,
        onClick: () => {
          vm.$router.push(`/order?orderId=${jsonMsg.orderId}`).catch((error: Error) => {
            console.log(error)
          })
          setTimeout(() => {
            location.reload()
          }, 100)
        },
        message:
          jsonMsg.type === 1
            ? `<span>您有 1 条<span style="color:#419EFF">订单待处理</span>，${jsonMsg.content}</span>`
            : `${jsonMsg.content}<span style="color:#419EFF;cursor: pointer"> 立即处理</span>`,
      })
    }

    this.websocket.onerror = () => {
      vm.$notify({
        title: '错误',
        message: '服务连接异常，暂时无法接收实时提醒。',
        type: 'error',
        duration: 0,
      })
    }

    this.websocket.onclose = () => {
      console.log('WebSocket 已关闭')
    }
  }

  private toggleSideBar() {
    AppModule.ToggleSideBar(false)
  }

  private async logout() {
    this.shopShow = false
    this.$store.dispatch('LogOut').then(() => {
      this.$router.replace({ path: '/login' })
    })
  }

  private async getStatus() {
    try {
      const { data } = await getStatus()
      this.status = typeof data.data === 'number' ? data.data : 1
      this.setStatus = this.status
    } catch (error) {
      this.status = 1
      this.setStatus = 1
    }
  }

  private handleDocumentClick(event: MouseEvent) {
    const wrapper = this.$refs.profileMenu as HTMLElement | undefined
    if (!wrapper) {
      return
    }
    if (!wrapper.contains(event.target as Node)) {
      this.shopShow = false
    }
  }

  private toggleProfileMenu() {
    this.shopShow = !this.shopShow
  }

  private handleStatus() {
    this.setStatus = this.status
    this.dialogVisible = true
  }

  private async handleSave() {
    const { data } = await setStatus(this.setStatus)
    if (data.code === 1) {
      this.status = this.setStatus
      this.dialogVisible = false
      this.getStatus()
    }
  }

  private handlePwd() {
    this.shopShow = false
    this.dialogFormVisible = true
  }

  private handlePwdClose() {
    this.dialogFormVisible = false
  }
}
</script>

<style lang="scss" scoped>
.navbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  padding: 14px 20px;
  border-radius: 24px;
  background: rgba(255, 252, 246, 0.82);
  border: 1px solid rgba(255, 255, 255, 0.62);
  backdrop-filter: blur(18px);
  box-shadow: 0 18px 40px rgba(18, 27, 45, 0.08);
}

.nav-left,
.right-menu {
  display: flex;
  align-items: center;
}

.nav-left {
  gap: 14px;
  min-width: 0;
}

.nav-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 16px;
  background: linear-gradient(135deg, #171f2f, #273247);
  color: #ffffff;
  box-shadow: 0 14px 26px rgba(17, 27, 46, 0.18);
}

.hamburger-container {
  padding: 0;
  color: inherit;
}

.nav-brand {
  min-width: 0;
}

.nav-kicker {
  display: inline-flex;
  color: #8a94a8;
  font-size: 10px;
  letter-spacing: 0.2em;
}

.nav-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;

  h1 {
    margin: 0;
    color: #172033;
    font-size: 24px;
    line-height: 1.15;
  }
}

.business-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 74px;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.business-pill.is-open {
  background: rgba(27, 199, 121, 0.14);
  color: #09814b;
}

.business-pill.is-closed {
  background: rgba(245, 108, 108, 0.14);
  color: #c43f3f;
}

.right-menu {
  gap: 12px;
  flex: 0 0 auto;
}

.nav-action,
.profile-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 16px;
  border: none;
  border-radius: 14px;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease, background-color 0.2s ease;
}

.nav-action {
  background: linear-gradient(135deg, #ffbf3a, #ff8d2d);
  color: #172033;
  font-weight: 700;
  box-shadow: 0 14px 24px rgba(255, 167, 51, 0.2);

  i {
    font-size: 18px;
  }
}

.profile-button {
  min-width: 122px;
  justify-content: space-between;
  background: #ffffff;
  color: #172033;
  box-shadow: inset 0 0 0 1px rgba(216, 222, 232, 0.9);
}

.nav-action:hover,
.profile-button:hover,
.profile-button.active {
  transform: translateY(-1px);
}

.avatar-wrapper {
  position: relative;
}

.profile-arrow {
  font-size: 14px;
  transition: transform 0.2s ease;
}

.profile-button.active .profile-arrow {
  transform: rotate(180deg);
}

.userList {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 168px;
  padding: 8px;
  border-radius: 18px;
  background: rgba(255, 252, 246, 0.98);
  border: 1px solid rgba(221, 227, 239, 0.9);
  box-shadow: 0 20px 40px rgba(18, 27, 45, 0.12);
}

.menu-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  margin: 0;
  padding: 0 12px;
  border-radius: 12px;
  color: #394356;
  cursor: pointer;
  transition: background-color 0.2s ease, color 0.2s ease;

  i {
    font-size: 16px;
  }

  &:hover {
    background: #f4f6fb;
    color: #172033;
  }
}

@media (max-width: 1080px) {
  .navbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .right-menu {
    width: 100%;
    justify-content: space-between;
  }

  .nav-title-row {
    flex-wrap: wrap;
  }
}

@media (max-width: 720px) {
  .navbar {
    padding: 16px 18px;
    border-radius: 24px;
  }

  .nav-left,
  .right-menu {
    width: 100%;
    flex-wrap: wrap;
  }

  .nav-title-row h1 {
    font-size: 22px;
  }
}
</style>

<style lang="scss">
.el-notification {
  width: 400px !important;
  border-radius: 22px !important;
  border: 1px solid rgba(221, 227, 239, 0.86) !important;
  box-shadow: 0 18px 40px rgba(17, 26, 46, 0.12) !important;
}

.business-status-dialog {
  width: min(420px, calc(100vw - 32px)) !important;
  max-width: calc(100vw - 32px);
  border-radius: 28px;
  overflow: hidden;

  .el-dialog__header {
    padding: 22px 28px 10px;
    background: linear-gradient(180deg, #fffaf2 0%, #fff 100%);
    border: 0;
  }

  .el-dialog__title {
    color: #172033;
    font-size: 20px;
    font-weight: 700;
  }

  .el-dialog__body {
    padding: 6px 28px 18px;

    .el-radio-group {
      width: 100%;
    }

    .el-radio {
      display: block;
      width: 100%;
      margin: 14px 0 0;
      padding: 18px 20px;
      border-radius: 20px;
      background: #f7f9fc;
      border: 1px solid #e6ebf4;
      white-space: normal;
    }

    .el-radio__label {
      display: inline-block;
      color: #172033;
      font-weight: 700;

      span {
        display: block;
        margin-top: 8px;
        color: #6d7688;
        font-weight: 400;
        line-height: 1.7;
      }
    }

    .el-radio__input.is-checked + .el-radio__label {
      color: #172033;
    }
  }

  .el-dialog__footer {
    padding: 0 28px 24px;
  }

  .dialog-footer .el-button--primary {
    border: none;
    background: linear-gradient(135deg, #ffbf3a, #ff8d2d) !important;
    color: #172033 !important;
    font-weight: 700;
  }
}
</style>
