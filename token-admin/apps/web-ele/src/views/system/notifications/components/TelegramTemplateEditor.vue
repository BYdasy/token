<script lang="ts" setup>
import { nextTick, ref } from 'vue';

import { useI18n } from '@vben/locales';

import { ElButton } from 'element-plus';

const props = defineProps<{
  disabled?: boolean;
  modelValue?: string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const { t } = useI18n();
const textarea = ref<HTMLTextAreaElement>();

function updateValue(value: string) {
  if (props.disabled) return;
  emit('update:modelValue', value);
}

async function wrapSelection(prefix: string, suffix: string, fallback: string) {
  if (props.disabled) return;
  const input = textarea.value;
  if (!input) return;
  const value = props.modelValue ?? '';
  const start = input.selectionStart ?? value.length;
  const end = input.selectionEnd ?? start;
  const selected = value.slice(start, end) || fallback;
  const nextValue = `${value.slice(0, start)}${prefix}${selected}${suffix}${value.slice(end)}`;
  updateValue(nextValue);
  await nextTick();
  input.focus();
  input.setSelectionRange(start + prefix.length, start + prefix.length + selected.length);
}

function insertLink() {
  void wrapSelection('<a href="https://">', '</a>', t('admin.notification.telegramLinkText'));
}
</script>

<template>
  <div class="telegram-editor">
    <div class="telegram-toolbar">
      <ElButton
        :aria-label="t('admin.notification.telegramBold')"
        :disabled="disabled"
        :title="t('admin.notification.telegramBold')"
        @click="wrapSelection('<b>', '</b>', t('admin.notification.telegramExampleText'))"
      >
        <strong>B</strong>
      </ElButton>
      <ElButton
        :aria-label="t('admin.notification.telegramItalic')"
        :disabled="disabled"
        :title="t('admin.notification.telegramItalic')"
        @click="wrapSelection('<i>', '</i>', t('admin.notification.telegramExampleText'))"
      >
        <em>I</em>
      </ElButton>
      <ElButton
        :aria-label="t('admin.notification.telegramUnderline')"
        :disabled="disabled"
        :title="t('admin.notification.telegramUnderline')"
        @click="wrapSelection('<u>', '</u>', t('admin.notification.telegramExampleText'))"
      >
        <u>U</u>
      </ElButton>
      <ElButton
        :aria-label="t('admin.notification.telegramStrike')"
        :disabled="disabled"
        :title="t('admin.notification.telegramStrike')"
        @click="wrapSelection('<s>', '</s>', t('admin.notification.telegramExampleText'))"
      >
        <s>S</s>
      </ElButton>
      <ElButton
        :aria-label="t('admin.notification.telegramCode')"
        :disabled="disabled"
        :title="t('admin.notification.telegramCode')"
        @click="wrapSelection('<code>', '</code>', t('admin.notification.telegramCodeText'))"
      >
        `code`
      </ElButton>
      <ElButton
        :aria-label="t('admin.notification.telegramPre')"
        :disabled="disabled"
        :title="t('admin.notification.telegramPre')"
        @click="wrapSelection('<pre><code>', '</code></pre>', t('admin.notification.telegramCodeText'))"
      >
        &lt;pre&gt;
      </ElButton>
      <ElButton
        :aria-label="t('admin.notification.telegramLink')"
        :disabled="disabled"
        :title="t('admin.notification.telegramLink')"
        @click="insertLink"
      >
        🔗
      </ElButton>
    </div>
    <textarea
      ref="textarea"
      class="telegram-textarea"
      :disabled="disabled"
      :placeholder="t('admin.notification.telegramEditorPlaceholder')"
      :value="modelValue"
      @input="updateValue(($event.target as HTMLTextAreaElement).value)"
    ></textarea>
    <div class="telegram-help">
      {{ t('admin.notification.telegramEditorHelp') }}
    </div>
  </div>
</template>

<style scoped>
.telegram-editor {
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.telegram-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
}

.telegram-toolbar :deep(.el-button) {
  min-width: 34px;
  height: 30px;
  padding: 0 9px;
  margin-left: 0;
}

.telegram-textarea {
  display: block;
  width: 100%;
  min-height: 260px;
  padding: 12px;
  border: 0;
  outline: none;
  resize: vertical;
  background: transparent;
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 14px;
  line-height: 1.65;
}

.telegram-textarea:disabled {
  cursor: not-allowed;
  background: var(--el-disabled-bg-color);
  color: var(--el-disabled-text-color);
}

.telegram-textarea:focus-visible {
  outline: 2px solid var(--exp-primary);
  outline-offset: -2px;
}

.telegram-help {
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
