<template>
  <a-layout style="min-height: 100vh;">
    <!-- 左侧菜单固定定位，不随右侧内容区域滚动 -->
    <a-layout-sider
      v-model:collapsed="collapsed"
      collapsible
      trigger="null"
      width="220"
      collapsed-width="64"
      class="fixed-sider"
    >
      <div :class="['logo', { collapsed }]">{{ collapsed ? 'EF' : 'EvalForge' }}</div>
      <a-menu :selectedKeys="sideMenuKeys" theme="dark" mode="inline">
        <a-menu-item key="model-eval" @click="router.push('/model-eval/groups')">
          <template #icon><ClusterOutlined /></template>
          <span>模型评估</span>
        </a-menu-item>
        <a-menu-item key="conversation" @click="router.push('/agent-eval/conversation')">
          <template #icon><MessageOutlined /></template>
          <span>会话</span>
        </a-menu-item>
        <a-menu-item key="agent-eval" @click="router.push('/agent-eval/datasets')">
          <template #icon><ExperimentOutlined /></template>
          <span>评测中心</span>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <!-- 为固定侧栏留出空间，并随折叠状态调整宽度 -->
    <div :style="{ width: collapsed ? '64px' : '220px', flexShrink: 0, transition: 'width 0.2s' }" />

    <a-layout>
      <!-- 顶部子菜单 -->
      <a-layout-header v-if="route.meta.topMenu && !route.meta.hideTopMenu" class="top-menu-bar">
        <div style="display: flex; align-items: center; height: 100%;">
          <a-menu
            v-if="currentSideMenu === 'agent-eval'"
            :selectedKeys="topMenuKeys"
            mode="horizontal"
            style="flex: 1; border: none; line-height: 64px;"
          >
            <a-menu-item key="datasets" @click="router.push('/agent-eval/datasets')">评测数据集管理</a-menu-item>
            <a-menu-item key="config" @click="router.push('/agent-eval/config')">配置中心</a-menu-item>
          </a-menu>
          <a-menu
            v-else-if="currentSideMenu === 'model-eval'"
            :selectedKeys="topMenuKeys"
            mode="horizontal"
            style="flex: 1; border: none; line-height: 64px;"
          >
            <a-menu-item key="groups" @click="router.push('/model-eval/groups')">模型分组</a-menu-item>
          </a-menu>
          <a-button
            v-if="currentSideMenu === 'agent-eval'"
            type="text"
            @click="router.push('/agent-eval/records')"
          >
            <template #icon><UnorderedListOutlined /></template>
            评测记录
          </a-button>
          <a-button type="text" @click="collapsed = !collapsed">
            <MenuUnfoldOutlined v-if="collapsed" />
            <MenuFoldOutlined v-else />
          </a-button>
        </div>
      </a-layout-header>

      <!-- 详情页面包屑导航 -->
      <a-layout-header v-else-if="route.meta.hideTopMenu" class="top-menu-bar" style="padding: 0 24px;">
        <div style="display: flex; align-items: center; height: 100%;">
          <a-breadcrumb style="flex: 1;">
            <template v-if="currentSideMenu === 'model-eval'">
              <a-breadcrumb-item>
                <a @click="router.push('/model-eval/groups')">模型分组</a>
              </a-breadcrumb-item>
              <a-breadcrumb-item>{{ route.meta.title }}</a-breadcrumb-item>
            </template>
            <template v-else-if="route.query.from === 'model-eval'">
              <a-breadcrumb-item>
                <a @click="router.push('/model-eval/groups')">模型分组</a>
              </a-breadcrumb-item>
              <a-breadcrumb-item v-if="route.query.groupId">
                <a @click="router.push(`/model-eval/groups/${route.query.groupId}`)">子评测</a>
              </a-breadcrumb-item>
            </template>
            <a-breadcrumb-item v-else>
              <a @click="router.push('/agent-eval/records')">评测记录</a>
            </a-breadcrumb-item>
            <a-breadcrumb-item>{{ route.meta.title }}</a-breadcrumb-item>
          </a-breadcrumb>
          <a-button type="text" @click="collapsed = !collapsed">
            <MenuUnfoldOutlined v-if="collapsed" />
            <MenuFoldOutlined v-else />
          </a-button>
        </div>
      </a-layout-header>

      <!-- 无顶部菜单的页面仅保留侧栏折叠按钮 -->
      <a-layout-header v-else class="top-menu-bar" style="padding: 0 16px;">
        <div style="display: flex; justify-content: flex-end; align-items: center; height: 100%;">
          <a-button type="text" @click="collapsed = !collapsed">
            <MenuUnfoldOutlined v-if="collapsed" />
            <MenuFoldOutlined v-else />
          </a-button>
        </div>
      </a-layout-header>

      <a-layout-content style="background: #f0f2f5; overflow: auto;">
        <router-view v-slot="{ Component }">
          <keep-alive :include="['RecordList']">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ExperimentOutlined,
  ClusterOutlined,
  MessageOutlined,
  UnorderedListOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons-vue'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const currentSideMenu = computed(() => route.meta.sideMenu || 'agent-eval')
const sideMenuKeys = computed(() => [currentSideMenu.value])
const topMenuKeys = computed(() => [route.meta.topMenu || 'records'])
</script>

<style scoped>
.fixed-sider {
  position: fixed;
  top: 0;
  left: 0;
  height: 100vh;
  overflow-y: auto;
  z-index: 10;
}

.top-menu-bar {
  background: #fff;
  padding: 0 16px;
  border-bottom: 1px solid #e8e8e8;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  height: 64px;
  line-height: 64px;
}
</style>
