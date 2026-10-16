<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useI18n } from '@vben/locales';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElRadioButton,
  ElRadioGroup,
  ElSwitch,
  ElTag,
  ElTooltip,
} from 'element-plus';

import { fetchSiteSettings, updateSiteSettings } from '#/api/operations';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';
import { applySiteBranding } from '#/utils/site-branding';
import AvatarPicker from '#/components/business/BusinessImagePicker.vue';
import RemoteTimeZoneSelect from '#/components/RemoteTimeZoneSelect.vue';
import IdleSessionSettings from './IdleSessionSettings.vue';
import SharePreviewSettings from './SharePreviewSettings.vue';

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const model = reactive({
  cacheEnabled: true,
  cacheIpWhitelistEnabled: true,
  cacheLanguagesEnabled: true,
  cacheMenusEnabled: true,
  cacheMode: 'MEMORY' as 'AUTO' | 'MEMORY' | 'REDIS',
  cacheRolesEnabled: true,
  cacheSettingsEnabled: true,
  dateTimeFormat: 'yyyy-MM-dd HH:mm:ss',
  siteIcon: '',
  siteName: '',
  siteLogo: '',
  userSiteName: '',
  userSiteLogo: '',
  userSiteIcon: '',
  userHeroImage: '',
  timeZone: 'Asia/Shanghai',
  serverDisplayTimeZone: 'Asia/Shanghai',
});
const cacheStatus = reactive({
  effectiveMode: 'MEMORY' as 'DISABLED' | 'MEMORY' | 'REDIS',
  localEntryCount: 0,
  redisAvailable: false,
});

async function loadData() {
  loading.value = true;
  try {
    const data = await fetchSiteSettings();
    model.siteName = data.siteName || '';
    model.siteIcon = data.siteIcon || '';
    model.siteLogo = data.siteLogo || '';
    model.userSiteName = data.userSiteName || '';
    model.userSiteLogo = data.userSiteLogo || '';
    model.userSiteIcon = data.userSiteIcon || '';
    model.userHeroImage = data.userHeroImage || '';
    model.timeZone = data.timeZone || 'Asia/Shanghai';
    model.serverDisplayTimeZone = data.serverDisplayTimeZone || model.timeZone;
    model.dateTimeFormat = data.dateTimeFormat || 'yyyy-MM-dd HH:mm:ss';
    model.cacheEnabled = data.cache?.enabled ?? true;
    model.cacheMode = data.cache?.configuredMode ?? 'MEMORY';
    model.cacheSettingsEnabled = data.cache?.features?.SETTINGS ?? true;
    model.cacheLanguagesEnabled = data.cache?.features?.LANGUAGES ?? true;
    model.cacheMenusEnabled = data.cache?.features?.MENUS ?? true;
    model.cacheRolesEnabled = data.cache?.features?.ROLES ?? true;
    model.cacheIpWhitelistEnabled = data.cache?.features?.IP_WHITELIST ?? true;
    cacheStatus.effectiveMode = data.cache?.effectiveMode ?? 'MEMORY';
    cacheStatus.redisAvailable = data.cache?.redisAvailable ?? false;
    cacheStatus.localEntryCount = Number(data.cache?.localEntryCount ?? 0);
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  try {
    await updateSiteSettings({ ...model });
    applySiteBranding(model);
    ElMessage.success(t('admin.settings.saved'));
  } finally {
    saving.value = false;
  }
}

onMounted(loadData);
</script>

<template>
  <Page>
    <IdleSessionSettings />
    <SharePreviewSettings />
    <div v-loading="loading" class="settings-layout">
      <ElCard class="exp-card settings-card" shadow="never">
        <template #header>
          <div>
            <strong>{{ t('admin.settings.title') }}</strong>
            <p>{{ t('admin.settings.description') }}</p>
          </div>
        </template>
        <ElForm label-position="right" label-width="150px" :model="model">
          <div class="brand-settings-grid">
          <section>
          <h3>{{ t('admin.compute.v12.adminSettings') }}</h3>
          <ElFormItem :label="t('admin.settings.v5.adminName')">
            <ElInput v-model="model.siteName" maxlength="80" />
          </ElFormItem>
          <ElFormItem :label="t('admin.settings.v5.adminLogo')">
            <AvatarPicker v-model="model.siteLogo" />
          </ElFormItem>
          <ElFormItem :label="t('admin.settings.v5.adminIcon')">
            <AvatarPicker v-model="model.siteIcon" />
          </ElFormItem>
          </section>
          <section>
          <h3>{{ t('admin.compute.v12.userSettings') }}</h3>
          <ElFormItem :label="t('admin.settings.v5.userName')">
            <ElInput v-model="model.userSiteName" maxlength="80" />
          </ElFormItem>
          <ElFormItem :label="t('admin.settings.v5.userLogo')">
            <AvatarPicker v-model="model.userSiteLogo" />
          </ElFormItem>
          <ElFormItem :label="t('admin.settings.v5.userIcon')">
            <AvatarPicker v-model="model.userSiteIcon" />
          </ElFormItem>
          <ElFormItem :label="t('admin.settings.v5.userHero')">
            <AvatarPicker v-model="model.userHeroImage" />
            <div class="form-help">
              {{ t('admin.settings.v5.independentBranding') }}
            </div>
          </ElFormItem>
          </section>
          </div>
          <h3>{{ t('admin.compute.v12.sharedSettings') }}</h3>
          <ElFormItem :label="t('admin.settings.v9.businessZone')">
            <RemoteTimeZoneSelect v-model="model.timeZone" />
            <div class="form-help">{{ t('admin.settings.v9.businessZoneHelp') }}</div>
          </ElFormItem>
          <ElFormItem :label="t('admin.settings.v9.displayZone')">
            <RemoteTimeZoneSelect v-model="model.serverDisplayTimeZone" />
            <div class="form-help">{{ t('admin.settings.v9.displayZoneHelp') }}</div>
          </ElFormItem>
          <ElFormItem :label="t('admin.settings.dateTimeFormat')">
            <ElInput
              v-model="model.dateTimeFormat"
              placeholder="yyyy-MM-dd HH:mm:ss"
            />
            <div class="form-help">
              {{ t('admin.settings.dateTimeFormatHelp') }}
            </div>
          </ElFormItem>
          <ElFormItem>
            <ElButton
              v-access:code="
                permissionCodes(SYSTEM_PERMISSIONS.settings.update)
              "
              :loading="saving"
              type="primary"
              @click="save"
            >
              {{ t('admin.common.save') }}
            </ElButton>
          </ElFormItem>
        </ElForm>
      </ElCard>

      <ElCard class="exp-card settings-card" shadow="never">
        <template #header>
          <div class="cache-heading">
            <div>
              <strong>{{ t('admin.cache.title') }}</strong>
              <p>{{ t('admin.cache.description') }}</p>
            </div>
            <ElTag
              :type="cacheStatus.effectiveMode === 'REDIS' ? 'success' : 'info'"
            >
              {{
                t('admin.cache.effectiveMode', {
                  mode: cacheStatus.effectiveMode,
                })
              }}
            </ElTag>
          </div>
        </template>
        <ElForm label-position="right" label-width="170px" :model="model">
          <ElFormItem :label="t('admin.cache.enabled')">
            <ElSwitch v-model="model.cacheEnabled" />
          </ElFormItem>
          <ElFormItem :label="t('admin.cache.mode')">
            <ElRadioGroup
              v-model="model.cacheMode"
              :disabled="!model.cacheEnabled"
            >
              <ElRadioButton value="MEMORY">{{
                t('admin.cache.memory')
              }}</ElRadioButton>
              <ElRadioButton value="AUTO">{{
                t('admin.cache.auto')
              }}</ElRadioButton>
              <ElRadioButton value="REDIS">Redis</ElRadioButton>
            </ElRadioGroup>
            <div class="form-help">{{ t('admin.cache.modeHelp') }}</div>
          </ElFormItem>
          <ElAlert
            v-if="model.cacheMode !== 'MEMORY' && !cacheStatus.redisAvailable"
            :closable="false"
            :title="t('admin.cache.redisUnavailable')"
            type="warning"
          />
          <div class="cache-feature-grid">
            <label>
              <span class="cache-feature-name">
                {{ t('admin.cache.settings') }}
                <ElTooltip
                  :content="t('admin.cache.settingsDetail')"
                  placement="top"
                >
                  <IconifyIcon
                    class="cache-help-icon"
                    icon="lucide:circle-help"
                  />
                </ElTooltip>
              </span>
              <ElSwitch
                v-model="model.cacheSettingsEnabled"
                :disabled="!model.cacheEnabled"
              />
            </label>
            <label>
              <span class="cache-feature-name">
                {{ t('admin.cache.v40.languages') }}
                <ElTooltip
                  :content="t('admin.cache.v40.languagesDetail')"
                  placement="top"
                >
                  <IconifyIcon
                    class="cache-help-icon"
                    icon="lucide:circle-help"
                  />
                </ElTooltip>
              </span>
              <ElSwitch
                v-model="model.cacheLanguagesEnabled"
                :disabled="!model.cacheEnabled"
              />
            </label>
            <label>
              <span class="cache-feature-name">
                {{ t('admin.cache.menus') }}
                <ElTooltip
                  :content="t('admin.cache.menusDetail')"
                  placement="top"
                >
                  <IconifyIcon
                    class="cache-help-icon"
                    icon="lucide:circle-help"
                  />
                </ElTooltip>
              </span>
              <ElSwitch
                v-model="model.cacheMenusEnabled"
                :disabled="!model.cacheEnabled"
              />
            </label>
            <label>
              <span class="cache-feature-name">
                {{ t('admin.cache.roles') }}
                <ElTooltip
                  :content="t('admin.cache.rolesDetail')"
                  placement="top"
                >
                  <IconifyIcon
                    class="cache-help-icon"
                    icon="lucide:circle-help"
                  />
                </ElTooltip>
              </span>
              <ElSwitch
                v-model="model.cacheRolesEnabled"
                :disabled="!model.cacheEnabled"
              />
            </label>
            <label>
              <span class="cache-feature-name">
                {{ t('admin.cache.ipWhitelist') }}
                <ElTooltip
                  :content="t('admin.cache.ipWhitelistDetail')"
                  placement="top"
                >
                  <IconifyIcon
                    class="cache-help-icon"
                    icon="lucide:circle-help"
                  />
                </ElTooltip>
              </span>
              <ElSwitch
                v-model="model.cacheIpWhitelistEnabled"
                :disabled="!model.cacheEnabled"
              />
            </label>
          </div>
          <ElFormItem>
            <ElButton
              v-access:code="
                permissionCodes(SYSTEM_PERMISSIONS.settings.update)
              "
              :loading="saving"
              type="primary"
              @click="save"
            >
              {{ t('admin.common.save') }}
            </ElButton>
          </ElFormItem>
        </ElForm>
      </ElCard>
    </div>
  </Page>
</template>

<style scoped>
.settings-layout {
  display: grid;
  width: 100%;
  min-width: 0;
  gap: 18px;
}
.settings-card {
  width: 100%;
  min-width: 0;
  container: settings-card / inline-size;
}
.settings-card p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: normal;
}
.brand-settings-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 360px), 1fr)); gap: 24px; }
.brand-settings-grid section { min-width: 0; padding: 16px 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.settings-card h3 { font-size: 15px; font-weight: 600; margin: 4px 0 20px; }
.settings-card :deep(.el-input), .settings-card :deep(.el-select) { width: 320px; max-width: 100%; }
.form-help {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.cache-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.cache-heading > div { min-width: 0; flex: 1 1 240px; }
.cache-heading :deep(.el-tag) { max-width: 100%; height: auto; min-height: 24px; white-space: normal; overflow-wrap: anywhere; }
.cache-feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr));
  gap: 10px;
  margin: 16px 0 20px 170px;
}
.cache-feature-grid label {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.cache-feature-name {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  overflow-wrap: anywhere;
}
.cache-feature-grid :deep(.el-switch) { flex-shrink: 0; }
.cache-help-icon {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  cursor: help;
  font-size: 15px;
}
@container settings-card (max-width: 640px) {
  .settings-card :deep(.el-form-item) { display: block; }
  .settings-card :deep(.el-form-item__label) { justify-content: flex-start; width: auto !important; height: auto; padding: 0; margin-bottom: 8px; line-height: 1.5; text-align: left; }
  .settings-card :deep(.el-form-item__content) { margin-left: 0 !important; }
  .settings-card :deep(.el-radio-group) { display: flex; flex-wrap: wrap; gap: 6px 0; }
  .cache-feature-grid {
    grid-template-columns: 1fr;
    margin-left: 0;
  }
}
</style>
