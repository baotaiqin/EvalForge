import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/model-eval/groups',
    children: [
      {
        path: '/agent-eval/records',
        name: 'EvalRecords',
        component: () => import('@/views/evaluation/RecordList.vue'),
        meta: { sideMenu: 'agent-eval', topMenu: 'records', title: '评测记录' }
      },
      {
        path: '/agent-eval/records/:id',
        name: 'EvalDetail',
        component: () => import('@/views/evaluation/RecordDetail.vue'),
        meta: { sideMenu: 'agent-eval', topMenu: 'records', title: '评测详情', hideTopMenu: true }
      },
      {
        path: '/agent-eval/datasets',
        name: 'Datasets',
        component: () => import('@/views/dataset/DatasetList.vue'),
        meta: { sideMenu: 'agent-eval', topMenu: 'datasets', title: '评测数据集管理' }
      },
      {
        path: '/agent-eval/config',
        name: 'ConfigCenter',
        component: () => import('@/views/config/ConfigCenter.vue'),
        meta: { sideMenu: 'agent-eval', topMenu: 'config', title: '配置中心' }
      },
      {
        path: '/agent-eval/conversation',
        name: 'Conversation',
        component: () => import('@/views/conversation/ConversationView.vue'),
        meta: { sideMenu: 'agent-eval', topMenu: 'datasets', title: '评测数据集管理' }
      },
      {
        path: '/model-eval/groups',
        name: 'ModelGroups',
        component: () => import('@/views/modelEval/ModelGroupList.vue'),
        meta: { sideMenu: 'model-eval', topMenu: 'groups', title: '模型分组' }
      },
      {
        path: '/model-eval/groups/:groupId',
        name: 'SubEvaluationList',
        component: () => import('@/views/modelEval/SubEvaluationList.vue'),
        meta: { sideMenu: 'model-eval', topMenu: 'groups', title: '子评测列表', hideTopMenu: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
