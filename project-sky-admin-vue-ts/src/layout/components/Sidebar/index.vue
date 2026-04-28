<template>
  <div class="sidebar-shell">
    <div class="logo">
      <div v-if="!isCollapse" class="sidebar-brand">
        <div class="brand-mark">小</div>
        <div class="brand-copy">
          <span class="brand-name">小威外卖</span>
          <span class="brand-subtitle">商家后台控制台</span>
        </div>
      </div>
      <div v-else class="sidebar-brand-mini">
        <div class="brand-mark">小</div>
      </div>
    </div>

    <el-scrollbar wrap-class="scrollbar-wrapper">
      <el-menu
        :default-openeds="defOpen"
        :default-active="defAct"
        :collapse="isCollapse"
        :background-color="variables.menuBg"
        :text-color="variables.menuText"
        :active-text-color="variables.menuActiveText"
        :unique-opened="false"
        :collapse-transition="false"
        mode="vertical"
      >
        <sidebar-item
          v-for="route in routes"
          :key="route.path"
          :item="route"
          :base-path="route.path"
          :is-collapse="isCollapse"
        />
      </el-menu>
    </el-scrollbar>
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-property-decorator'
import { AppModule } from '@/store/modules/app'
import { UserModule } from '@/store/modules/user'
import SidebarItem from './SidebarItem.vue'
import variables from '@/styles/_variables.scss'

@Component({
  name: 'SideBar',
  components: {
    SidebarItem,
  },
})
export default class extends Vue {
  get defOpen() {
    const path = ['/']
    this.routes.forEach((item: any) => {
      if (item.meta.roles && item.meta.roles[0] === this.roles[0]) {
        path.splice(0, 1, item.path)
      }
    })
    return path
  }

  get defAct() {
    return this.$route.path
  }

  get sidebar() {
    return AppModule.sidebar
  }

  get roles() {
    return UserModule.roles
  }

  get routes() {
    const routes = JSON.parse(JSON.stringify([...(this.$router as any).options.routes]))
    const menu = routes.find((item: any) => item.path === '/')
    return menu ? menu.children : []
  }

  get variables() {
    return variables
  }

  get isCollapse() {
    return !this.sidebar.opened
  }
}
</script>

<style lang="scss" scoped>
.sidebar-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 10px 12px 12px;
  border-radius: 28px;
  background:
    radial-gradient(circle at top, rgba(255, 191, 58, 0.12), transparent 32%),
    linear-gradient(180deg, #131a29 0%, #1b2436 38%, #101724 100%);
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 28px 60px rgba(10, 18, 33, 0.24);
}

.logo {
  flex: 0 0 auto;
}

.sidebar-brand,
.sidebar-brand-mini {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 64px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.sidebar-brand {
  justify-content: flex-start;
  gap: 12px;
  padding: 0 14px;
}

.sidebar-brand-mini {
  padding: 0;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 14px;
  background: linear-gradient(135deg, #ffbf3a, #ff8d2d);
  color: #172033;
  font-size: 20px;
  font-weight: 800;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.3);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.brand-name {
  color: #ffffff;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.1;
}

.brand-subtitle {
  margin-top: 4px;
  color: rgba(215, 223, 239, 0.7);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.el-scrollbar {
  height: calc(100% - 78px);
  margin-top: 12px;
  background: transparent;
}

.el-menu {
  border: none;
  height: 100%;
  width: 100% !important;
  padding: 8px 4px 10px;
  background: transparent !important;
}
</style>
