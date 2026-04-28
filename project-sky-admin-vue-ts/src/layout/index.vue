<template>
  <div :class="classObj" class="app-wrapper">
    <div
      v-if="classObj.mobile && sidebar.opened"
      class="drawer-bg"
      @click="handleClickOutside"
    />
    <sidebar class="sidebar-container" />
    <div class="main-container">
      <navbar />
      <app-main />
    </div>
  </div>
</template>

<script lang="ts">
import { Component } from 'vue-property-decorator'
import { mixins } from 'vue-class-component'
import { DeviceType, AppModule } from '@/store/modules/app'
import { AppMain, Navbar, Sidebar } from './components'
import ResizeMixin from './mixin/resize'

@Component({
  name: 'Layout',
  components: {
    AppMain,
    Navbar,
    Sidebar,
  },
})
export default class extends mixins(ResizeMixin) {
  get classObj() {
    return {
      hideSidebar: !this.sidebar.opened,
      openSidebar: this.sidebar.opened,
      withoutAnimation: this.sidebar.withoutAnimation,
      mobile: this.device === DeviceType.Mobile,
    }
  }

  private handleClickOutside() {
    AppModule.CloseSideBar(false)
  }
}
</script>

<style lang="scss" scoped>
.app-wrapper {
  @include clearfix;
  position: relative;
  height: 100%;
  width: 100%;
  min-width: 1280px;
  overflow: hidden;
  background:
    radial-gradient(circle at 6% 10%, rgba(255, 191, 58, 0.2), transparent 18%),
    radial-gradient(circle at 94% 18%, rgba(77, 120, 255, 0.14), transparent 20%),
    linear-gradient(180deg, #eef3f8 0%, #f6f8fb 52%, #eff3f7 100%);
}

.drawer-bg {
  background: #000;
  opacity: 0.3;
  width: 100%;
  top: 0;
  height: 100%;
  position: absolute;
  z-index: 999;
}

.main-container {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  transition: margin-left 0.28s;
  margin-left: $sideBarWidth;
  width: calc(100% - #{$sideBarWidth});
  padding: 12px 14px 12px 0;
  box-sizing: border-box;
  background: transparent;
  position: relative;
  overflow: hidden;
}

.sidebar-container {
  transition: width 0.28s;
  width: $sideBarWidth !important;
  height: 100%;
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 1001;
  overflow: hidden;
  padding: 12px 0 12px 12px;
  background: transparent;
}

.hideSidebar {
  .main-container {
    margin-left: 84px;
    width: calc(100% - 84px);
  }

  .sidebar-container {
    width: 84px !important;
  }
}

/* for mobile response 适配移动端 */
.mobile {
  .main-container {
    margin-left: 0px;
    width: 100%;
    padding: 12px;
  }

  .sidebar-container {
    transition: transform 0.28s;
    width: $sideBarWidth !important;
    padding: 12px 0 12px 12px;
  }

  &.openSidebar {
    position: fixed;
    top: 0;
  }

  &.hideSidebar {
    .sidebar-container {
      pointer-events: none;
      transition-duration: 0.3s;
      transform: translate3d(-$sideBarWidth, 0, 0);
    }
  }
}

.withoutAnimation {
  .main-container,
  .sidebar-container {
    transition: none;
  }
}
</style>
