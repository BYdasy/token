<script lang="ts" setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useUserStore } from '@vben/stores';

import { ElButton, ElCard, ElProgress, ElTag } from 'element-plus';

defineOptions({ name: 'DashboardOverview' });

const router = useRouter();
const userStore = useUserStore();

const displayName = computed(
  () => userStore.userInfo?.realName || userStore.userInfo?.username || '管理员',
);

const formattedDate = new Intl.DateTimeFormat('zh-CN', {
  day: 'numeric',
  month: 'long',
  weekday: 'long',
}).format(new Date());

const metrics = [
  {
    icon: 'lucide:blocks',
    label: '通用模块',
    note: '账户、角色、日志、系统设置',
    tone: 'blue',
    value: '4',
  },
  {
    icon: 'lucide:shield-check',
    label: '权限体系',
    note: '路由与按钮级访问控制',
    tone: 'violet',
    value: 'RBAC',
  },
  {
    icon: 'lucide:monitor-smartphone',
    label: '响应式体验',
    note: '桌面、平板与移动端适配',
    tone: 'cyan',
    value: '100%',
  },
  {
    icon: 'lucide:unplug',
    label: '业务耦合',
    note: '首页不依赖业务接口',
    tone: 'green',
    value: '0',
  },
];

const quickActions = [
  {
    description: '维护登录账号、角色与启用状态',
    icon: 'lucide:users',
    path: '/system/account',
    title: '账号管理',
  },
  {
    description: '配置角色及菜单操作权限',
    icon: 'lucide:shield',
    path: '/system/role',
    title: '角色权限',
  },
  {
    description: '追踪后台关键操作记录',
    icon: 'lucide:file-clock',
    path: '/system/log',
    title: '操作日志',
  },
  {
    description: '维护多语言资源和文案',
    icon: 'lucide:languages',
    path: '/system/translates',
    title: '翻译管理',
  },
];

const checklist = [
  { done: true, text: '通用认证与权限守卫已就绪' },
  { done: true, text: '系统管理基础模块已接入' },
  { done: true, text: '主题、国际化与响应式已配置' },
  { done: false, text: '在此模板上接入你的业务模块' },
];

const runtimeItems = [
  { label: '前端框架', value: 'Vue 3 + TypeScript' },
  { label: '组件体系', value: 'Element Plus' },
  { label: '接口前缀', value: import.meta.env.VITE_GLOB_API_URL || '/api' },
  { label: '运行模式', value: import.meta.env.MODE },
];

function navigate(path: string) {
  void router.push(path);
}
</script>

<template>
  <Page auto-content-height>
    <div class="exp-home">
      <section class="hero-panel">
        <div class="hero-copy">
          <div class="eyebrow"><span></span> duotaivora 基础模板</div>
          <p class="hero-date">{{ formattedDate }}</p>
          <h1>
            欢迎回来，{{ displayName }}
            <span>从一个干净的基础开始。</span>
          </h1>
          <p class="hero-description">
            duotaivora 已整理好认证、权限、系统管理和工程化配置。你可以把精力集中在真正的业务功能上。
          </p>
          <div class="hero-actions">
            <ElButton type="primary" @click="navigate('/system/account')">
              管理系统账号
              <IconifyIcon icon="lucide:arrow-up-right" />
            </ElButton>
            <ElButton @click="navigate('/system/log')">
              查看操作日志
            </ElButton>
          </div>
        </div>

        <div class="hero-visual" aria-hidden="true">
          <div class="visual-glow"></div>
          <div class="code-window">
            <div class="window-bar">
              <span></span><span></span><span></span>
              <small>duotaivora.config.ts</small>
            </div>
            <div class="code-body">
              <p><i>const</i> starter = <b>{</b></p>
              <p class="indent">auth: <em>true</em>,</p>
              <p class="indent">rbac: <em>true</em>,</p>
              <p class="indent">business: <strong>'你的下一个创意'</strong>,</p>
              <p><b>}</b>;</p>
            </div>
            <div class="ready-line">
              <span><IconifyIcon icon="lucide:circle-check" /> 已就绪</span>
              <small>duotaivora v1.0</small>
            </div>
          </div>
        </div>
      </section>

      <section class="metric-grid" aria-label="模板能力概览">
        <ElCard v-for="item in metrics" :key="item.label" class="metric-card" shadow="never">
          <div class="metric-top">
            <span class="metric-icon" :class="`is-${item.tone}`">
              <IconifyIcon :icon="item.icon" />
            </span>
            <IconifyIcon class="metric-arrow" icon="lucide:move-up-right" />
          </div>
          <strong>{{ item.value }}</strong>
          <div class="metric-label">{{ item.label }}</div>
          <p>{{ item.note }}</p>
        </ElCard>
      </section>

      <section class="content-grid">
        <ElCard class="section-card quick-card" shadow="never">
          <template #header>
            <div class="section-heading">
              <div>
                <small>快捷入口</small>
                <h2>系统快捷入口</h2>
              </div>
              <ElTag effect="plain" round>基础能力</ElTag>
            </div>
          </template>
          <div class="quick-grid">
            <button
              v-for="item in quickActions"
              :key="item.path"
              class="quick-item"
              type="button"
              @click="navigate(item.path)"
            >
              <span class="quick-icon"><IconifyIcon :icon="item.icon" /></span>
              <span class="quick-copy">
                <strong>{{ item.title }}</strong>
                <small>{{ item.description }}</small>
              </span>
              <IconifyIcon class="quick-arrow" icon="lucide:chevron-right" />
            </button>
          </div>
        </ElCard>

        <ElCard class="section-card progress-card" shadow="never">
          <template #header>
            <div class="section-heading">
              <div>
                <small>开始使用</small>
                <h2>模板准备度</h2>
              </div>
              <strong class="progress-value">75%</strong>
            </div>
          </template>
          <ElProgress :percentage="75" :show-text="false" :stroke-width="8" />
          <div class="checklist">
            <div v-for="item in checklist" :key="item.text" :class="{ pending: !item.done }">
              <IconifyIcon :icon="item.done ? 'lucide:circle-check' : 'lucide:circle-dashed'" />
              <span>{{ item.text }}</span>
            </div>
          </div>
        </ElCard>
      </section>

      <ElCard class="section-card runtime-card" shadow="never">
        <div class="runtime-heading">
          <span class="runtime-icon"><IconifyIcon icon="lucide:terminal-square" /></span>
          <div><small>运行环境</small><h2>当前运行环境</h2></div>
        </div>
        <div class="runtime-grid">
          <div v-for="item in runtimeItems" :key="item.label">
            <small>{{ item.label }}</small>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
        <span class="status-pill"><i></i> 应用运行正常</span>
      </ElCard>
    </div>
  </Page>
</template>

<style scoped>
.exp-home {
  --home-border: var(--el-border-color-lighter);
  --home-muted: var(--el-text-color-secondary);
  --home-surface: var(--el-bg-color);
  width: 100%;
  min-height: 100%;
  padding: clamp(16px, 2.5vw, 30px);
  background:
    radial-gradient(circle at 3% 3%, rgb(79 70 229 / 7%), transparent 24rem),
    var(--el-fill-color-extra-light);
}

.hero-panel {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(320px, 0.8fr);
  gap: clamp(28px, 5vw, 72px);
  min-height: 356px;
  padding: clamp(30px, 5vw, 64px);
  overflow: hidden;
  color: #fff;
  background:
    linear-gradient(120deg, rgb(255 255 255 / 8%) 0 1px, transparent 1px 72px),
    linear-gradient(135deg, #111827 0%, #172554 56%, #312e81 100%);
  background-size: 72px 72px, auto;
  border: 1px solid rgb(255 255 255 / 8%);
  border-radius: 22px;
  box-shadow: 0 24px 70px rgb(15 23 42 / 16%);
}

.hero-panel::after {
  position: absolute;
  right: -100px;
  bottom: -180px;
  width: 430px;
  height: 430px;
  content: '';
  background: rgb(99 102 241 / 30%);
  border-radius: 50%;
  filter: blur(2px);
}

.hero-copy { position: relative; z-index: 2; align-self: center; }
.eyebrow { display: flex; gap: 10px; align-items: center; font-size: 11px; font-weight: 750; letter-spacing: .2em; color: #a5b4fc; }
.eyebrow span { width: 7px; height: 7px; background: #34d399; border-radius: 50%; box-shadow: 0 0 0 5px rgb(52 211 153 / 13%); }
.hero-date { margin: 24px 0 7px; font-size: 13px; color: #cbd5e1; }
.hero-copy h1 { max-width: 690px; margin: 0; font-size: clamp(30px, 4vw, 48px); font-weight: 760; line-height: 1.16; letter-spacing: -.035em; }
.hero-copy h1 span { display: block; color: #a5b4fc; }
.hero-description { max-width: 640px; margin: 22px 0 0; font-size: 15px; line-height: 1.8; color: #cbd5e1; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 30px; }
.hero-actions .el-button { height: 42px; padding: 0 19px; border-radius: 10px; }
.hero-actions .el-button--primary { background: #6366f1; border-color: #6366f1; }
.hero-actions svg { margin-left: 6px; }

.hero-visual { position: relative; z-index: 2; display: grid; place-items: center; min-width: 0; }
.visual-glow { position: absolute; width: 75%; aspect-ratio: 1; background: #818cf8; border-radius: 50%; opacity: .22; filter: blur(55px); }
.code-window { position: relative; width: min(100%, 430px); overflow: hidden; background: rgb(15 23 42 / 82%); border: 1px solid rgb(255 255 255 / 13%); border-radius: 15px; box-shadow: 0 25px 60px rgb(0 0 0 / 30%); backdrop-filter: blur(18px); transform: rotate(1.5deg); }
.window-bar { display: flex; gap: 7px; align-items: center; height: 43px; padding: 0 15px; background: rgb(255 255 255 / 5%); border-bottom: 1px solid rgb(255 255 255 / 8%); }
.window-bar > span { width: 8px; height: 8px; background: #fb7185; border-radius: 50%; }
.window-bar > span:nth-child(2) { background: #fbbf24; }
.window-bar > span:nth-child(3) { background: #34d399; }
.window-bar small { margin-left: auto; color: #94a3b8; }
.code-body { padding: 26px 28px; font-family: 'Cascadia Code', Consolas, monospace; font-size: 13px; line-height: 1.85; color: #cbd5e1; }
.code-body p { margin: 0; }
.code-body .indent { padding-left: 24px; }
.code-body i { font-style: normal; color: #c084fc; }
.code-body b { color: #f8fafc; }
.code-body em { font-style: normal; color: #38bdf8; }
.code-body strong { font-weight: 500; color: #86efac; }
.ready-line { display: flex; align-items: center; justify-content: space-between; padding: 13px 16px; font-size: 12px; color: #94a3b8; background: rgb(255 255 255 / 4%); border-top: 1px solid rgb(255 255 255 / 8%); }
.ready-line span { display: flex; gap: 6px; align-items: center; color: #6ee7b7; }

.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin-top: 18px; }
.metric-card.el-card, .section-card.el-card { background: var(--home-surface); border: 1px solid var(--home-border); border-radius: 16px; box-shadow: 0 8px 28px rgb(15 23 42 / 4%); }
.metric-card :deep(.el-card__body) { padding: 21px; }
.metric-top { display: flex; align-items: center; justify-content: space-between; }
.metric-icon { display: grid; width: 40px; height: 40px; font-size: 19px; color: #4f46e5; place-items: center; background: #eef2ff; border-radius: 11px; }
.metric-icon.is-violet { color: #7c3aed; background: #f5f3ff; }
.metric-icon.is-cyan { color: #0891b2; background: #ecfeff; }
.metric-icon.is-green { color: #059669; background: #ecfdf5; }
.metric-arrow { font-size: 15px; color: var(--home-muted); }
.metric-card strong { display: block; margin-top: 19px; font-size: 25px; color: var(--el-text-color-primary); letter-spacing: -.03em; }
.metric-label { margin-top: 3px; font-size: 14px; font-weight: 650; color: var(--el-text-color-primary); }
.metric-card p { margin: 7px 0 0; font-size: 12px; color: var(--home-muted); }

.content-grid { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(310px, .75fr); gap: 18px; margin-top: 18px; }
.section-card :deep(.el-card__header) { padding: 21px 23px 17px; border-bottom-color: var(--home-border); }
.section-heading { display: flex; gap: 16px; align-items: center; justify-content: space-between; }
.section-heading small, .runtime-heading small { font-size: 10px; font-weight: 750; color: #6366f1; letter-spacing: .14em; }
.section-heading h2, .runtime-heading h2 { margin: 4px 0 0; font-size: 17px; color: var(--el-text-color-primary); }
.quick-card :deep(.el-card__body) { padding: 8px 12px 13px; }
.quick-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.quick-item { display: flex; gap: 13px; align-items: center; min-width: 0; padding: 16px 12px; text-align: left; cursor: pointer; background: transparent; border: 0; border-radius: 11px; transition: background .2s ease, transform .2s ease; }
.quick-item:hover { background: var(--el-fill-color-light); transform: translateY(-1px); }
.quick-icon { display: grid; flex: none; width: 38px; height: 38px; font-size: 17px; color: #4f46e5; place-items: center; background: #eef2ff; border-radius: 10px; }
.quick-copy { display: flex; flex: 1; flex-direction: column; min-width: 0; }
.quick-copy strong { font-size: 14px; color: var(--el-text-color-primary); }
.quick-copy small { margin-top: 4px; overflow: hidden; color: var(--home-muted); text-overflow: ellipsis; white-space: nowrap; }
.quick-arrow { flex: none; color: var(--home-muted); }
.progress-value { font-size: 18px; color: #4f46e5; }
.progress-card :deep(.el-card__body) { padding: 22px 23px; }
.checklist { display: grid; gap: 17px; margin-top: 24px; }
.checklist > div { display: flex; gap: 10px; align-items: center; font-size: 13px; color: var(--el-text-color-regular); }
.checklist svg { flex: none; font-size: 17px; color: #10b981; }
.checklist .pending { color: var(--home-muted); }
.checklist .pending svg { color: #94a3b8; }

.runtime-card { margin-top: 18px; }
.runtime-card :deep(.el-card__body) { display: grid; grid-template-columns: 230px minmax(0, 1fr) auto; gap: 26px; align-items: center; padding: 22px 24px; }
.runtime-heading { display: flex; gap: 13px; align-items: center; }
.runtime-icon { display: grid; flex: none; width: 42px; height: 42px; font-size: 19px; color: #fff; place-items: center; background: #111827; border-radius: 11px; }
.runtime-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; }
.runtime-grid > div { min-width: 0; padding-left: 18px; border-left: 1px solid var(--home-border); }
.runtime-grid small { display: block; font-size: 11px; color: var(--home-muted); }
.runtime-grid strong { display: block; margin-top: 5px; overflow: hidden; font-size: 13px; color: var(--el-text-color-primary); text-overflow: ellipsis; white-space: nowrap; }
.status-pill { display: flex; gap: 8px; align-items: center; padding: 7px 11px; font-size: 11px; color: #047857; white-space: nowrap; background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: 999px; }
.status-pill i { width: 7px; height: 7px; background: #10b981; border-radius: 50%; box-shadow: 0 0 0 4px rgb(16 185 129 / 12%); }

@media (max-width: 1100px) {
  .hero-panel { grid-template-columns: 1fr; }
  .hero-visual { display: none; }
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .content-grid { grid-template-columns: 1fr; }
  .runtime-card :deep(.el-card__body) { grid-template-columns: 1fr; }
  .runtime-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .runtime-grid > div:first-child { padding-left: 0; border-left: 0; }
  .status-pill { width: fit-content; }
}

@media (max-width: 640px) {
  .exp-home { padding: 12px; }
  .hero-panel { min-height: auto; padding: 28px 22px; border-radius: 17px; }
  .hero-copy h1 { font-size: 29px; }
  .hero-actions { align-items: stretch; }
  .hero-actions .el-button { width: 100%; margin: 0; }
  .metric-grid { grid-template-columns: 1fr; gap: 12px; margin-top: 12px; }
  .content-grid { gap: 12px; margin-top: 12px; }
  .quick-grid { grid-template-columns: 1fr; }
  .runtime-card { margin-top: 12px; }
  .runtime-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .runtime-grid > div { padding-left: 0; border-left: 0; }
}
</style>
