<template>
  <div class="login">
    <div class="login-glow login-glow-left" />
    <div class="login-glow login-glow-right" />

    <div class="login-shell">
      <section class="login-showcase">
        <div class="brand-lockup">
          <div class="brand-mark">小</div>
          <div>
            <p class="brand-kicker">XIAOWEI DELIVERY CONSOLE</p>
            <h1>小威外卖商家后台</h1>
          </div>
        </div>

        <p class="brand-description">
          一套后台，统一管理订单、菜品、套餐、员工与智能客服，让日常运营更直接、更顺手。
        </p>

        <div class="feature-grid">
          <div class="feature-card">
            <span class="feature-index">01</span>
            <strong>订单响应更快</strong>
            <p>工作台、订单管理、营业状态一体联动。</p>
          </div>
          <div class="feature-card">
            <span class="feature-index">02</span>
            <strong>智能客服已接入</strong>
            <p>查询与受控写库都能在同一界面完成。</p>
          </div>
        </div>

        <div class="showcase-visual">
          <div class="visual-chip visual-chip-top">运营面板</div>
          <div class="visual-chip visual-chip-bottom">智能客服在线</div>
          <img src="@/assets/login/login-l.png" alt="小威外卖后台预览" />
        </div>
      </section>

      <section class="login-card">
        <div class="card-header">
          <div class="brand-mark brand-mark-small">小</div>
          <div>
            <p class="card-kicker">WELCOME BACK</p>
            <h2>登录小威外卖</h2>
          </div>
        </div>

        <p class="card-subtitle">请输入管理员账号和密码进入后台工作台。</p>

        <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="login-form">
          <el-form-item prop="username">
            <el-input
              v-model="loginForm.username"
              type="text"
              auto-complete="off"
              placeholder="请输入账号"
              prefix-icon="iconfont icon-user"
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入密码"
              prefix-icon="iconfont icon-lock"
              @keyup.enter.native="handleLogin"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              :loading="loading"
              class="login-btn"
              type="primary"
              @click.native.prevent="handleLogin"
            >
              <span v-if="!loading">进入后台</span>
              <span v-else>登录中...</span>
            </el-button>
          </el-form-item>
        </el-form>

        <div class="login-footer">
          <span>默认账号：`admin`</span>
          <span>默认密码：`123456`</span>
        </div>
      </section>
    </div>
  </div>
</template>

<script lang="ts">
import { Component, Vue, Watch } from 'vue-property-decorator'
import { Route } from 'vue-router'
import { Form as ElForm } from 'element-ui'
import { UserModule } from '@/store/modules/user'

@Component({
  name: 'Login',
})
export default class extends Vue {
  private validateUsername = (rule: any, value: string, callback: Function) => {
    if (!value) {
      callback(new Error('请输入用户名'))
    } else {
      callback()
    }
  }

  private validatePassword = (rule: any, value: string, callback: Function) => {
    if (value.length < 6) {
      callback(new Error('密码必须在6位以上'))
    } else {
      callback()
    }
  }

  private loginForm = {
    username: 'admin',
    password: '123456',
  } as {
    username: String
    password: String
  }

  loginRules = {
    username: [{ validator: this.validateUsername, trigger: 'blur' }],
    password: [{ validator: this.validatePassword, trigger: 'blur' }],
  }

  private loading = false
  private redirect?: string

  @Watch('$route', { immediate: true })
  private onRouteChange(route: Route) {}

  private handleLogin() {
    ;(this.$refs.loginForm as ElForm).validate(async (valid: boolean) => {
      if (valid) {
        this.loading = true
        await UserModule.Login(this.loginForm as any)
          .then((res: any) => {
            if (String(res.code) === '1') {
              this.$router.push('/')
            } else {
              this.loading = false
            }
          })
          .catch(() => {
            this.loading = false
          })
      } else {
        return false
      }
    })
  }
}
</script>

<style lang="scss">
.login {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 40px;
  overflow: hidden;
  background:
    radial-gradient(circle at 12% 18%, rgba(255, 184, 61, 0.28), transparent 18%),
    radial-gradient(circle at 82% 12%, rgba(55, 102, 255, 0.18), transparent 20%),
    linear-gradient(135deg, #171d2c 0%, #242d3f 55%, #101521 100%);
}

.login-glow {
  position: absolute;
  width: 360px;
  height: 360px;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.45;
  pointer-events: none;
}

.login-glow-left {
  left: -80px;
  bottom: -100px;
  background: #ffb646;
}

.login-glow-right {
  right: -120px;
  top: -80px;
  background: #5a7bff;
}

.login-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(420px, 1.2fr) minmax(360px, 420px);
  gap: 24px;
  width: min(1240px, 100%);
  align-items: stretch;
}

.login-showcase,
.login-card {
  border-radius: 30px;
  backdrop-filter: blur(20px);
  box-shadow: 0 24px 70px rgba(10, 15, 30, 0.28);
}

.login-showcase {
  position: relative;
  overflow: hidden;
  padding: 34px;
  color: #fff;
  background: linear-gradient(150deg, rgba(20, 25, 39, 0.9), rgba(31, 39, 58, 0.78));
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.brand-lockup {
  display: flex;
  align-items: center;
  gap: 16px;

  h1 {
    margin: 4px 0 0;
    font-size: 38px;
    line-height: 1.08;
    letter-spacing: 0.02em;
  }
}

.brand-kicker,
.card-kicker {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.28em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.62);
}

.brand-description {
  max-width: 520px;
  margin: 20px 0 0;
  font-size: 16px;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.76);
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 22px;
  background: linear-gradient(135deg, #ffbf3a, #ff8d2d);
  color: #161d2b;
  font-size: 30px;
  font-weight: 800;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.36);
}

.brand-mark-small {
  width: 52px;
  height: 52px;
  border-radius: 18px;
  font-size: 24px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 30px;
}

.feature-card {
  padding: 18px 18px 16px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.08);

  strong {
    display: block;
    margin-top: 10px;
    font-size: 18px;
  }

  p {
    margin: 10px 0 0;
    line-height: 1.7;
    color: rgba(255, 255, 255, 0.68);
  }
}

.feature-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(255, 191, 58, 0.16);
  color: #ffc35d;
  font-size: 12px;
  font-weight: 700;
}

.showcase-visual {
  position: relative;
  margin-top: 28px;
  padding: 24px 24px 18px;
  border-radius: 28px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), rgba(255, 255, 255, 0.02));

  img {
    display: block;
    width: 100%;
    border-radius: 24px;
    object-fit: cover;
    box-shadow: 0 20px 40px rgba(7, 12, 22, 0.32);
  }
}

.visual-chip {
  position: absolute;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  height: 38px;
  padding: 0 16px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  color: #162033;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 12px 32px rgba(16, 24, 40, 0.2);
}

.visual-chip-top {
  top: 10px;
  right: 28px;
}

.visual-chip-bottom {
  left: 38px;
  bottom: 26px;
}

.login-card {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 36px;
  background: rgba(255, 250, 242, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.52);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 14px;

  h2 {
    margin: 4px 0 0;
    font-size: 28px;
    color: #182033;
  }
}

.card-kicker {
  color: #8d98ad;
}

.card-subtitle {
  margin: 16px 0 0;
  line-height: 1.8;
  color: #5f6778;
}

.login-form {
  margin-top: 28px;

  .el-form-item {
    margin-bottom: 22px;
  }

  .el-input__inner {
    height: 52px;
    border: 1px solid #e6dfd2;
    border-radius: 16px;
    background: rgba(255, 255, 255, 0.92);
    color: #182033;
    font-size: 14px;
    padding-left: 42px;
    transition: border-color 0.2s ease, box-shadow 0.2s ease;

    &:focus {
      border-color: #ffb53f;
      box-shadow: 0 0 0 4px rgba(255, 193, 90, 0.18);
    }
  }

  .el-input__prefix {
    left: 14px;
    color: #99a0ad;
  }

  .el-input__icon {
    line-height: 52px;
  }
}

.login-btn {
  width: 100%;
  height: 52px;
  border: none;
  border-radius: 16px;
  background: linear-gradient(135deg, #ffbf3a, #ff8d2d) !important;
  color: #1b2336 !important;
  font-size: 15px;
  font-weight: 700;
  box-shadow: 0 16px 30px rgba(255, 167, 51, 0.25);

  &:hover,
  &:focus {
    transform: translateY(-1px);
    box-shadow: 0 18px 34px rgba(255, 167, 51, 0.3);
  }
}

.login-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  color: #8d98ad;
  font-size: 12px;
}

@media (max-width: 1080px) {
  .login {
    padding: 24px;
  }

  .login-shell {
    grid-template-columns: 1fr;
  }

  .brand-lockup h1 {
    font-size: 30px;
  }
}

@media (max-width: 640px) {
  .login {
    padding: 16px;
  }

  .login-showcase,
  .login-card {
    padding: 24px;
  }

  .feature-grid {
    grid-template-columns: 1fr;
  }

  .login-footer {
    flex-direction: column;
  }
}
</style>
