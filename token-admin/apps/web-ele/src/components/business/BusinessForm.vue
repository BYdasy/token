<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus';
import type { ComputeRecord } from '#/api/compute';
import type { ComputeField } from '#/components/business/shared';

import { computed, ref, useId } from 'vue';
import {
  ElDivider,
  ElDatePicker,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTimePicker,
} from 'element-plus';
import { useCompute } from '#/components/business/shared';

const props = defineProps<{
  model: ComputeRecord;
  fields: ComputeField[];
  disabled?: boolean;
}>();
const { c, enumLabel } = useCompute();
const formRef = ref<FormInstance>();
const formId = useId();
const fieldLabel = (field: ComputeField) => c(field.labelKey || (field.key === 'startsAt' ? 'common.rangeStart' : `field.${field.key}`));
const rules = computed<FormRules>(() =>
  Object.fromEntries(
    props.fields.map((field) => [
      field.key,
      [
        {
          trigger: ['blur', 'change'],
          validator: (
            _rule: unknown,
            value: unknown,
            callback: (error?: Error) => void,
          ) => {
            const empty =
              value === '' ||
              value === null ||
              value === undefined ||
              (Array.isArray(value) && value.length === 0);
            if (empty)
              return callback(
                field.required
                  ? new Error(c('validation.required'))
                  : undefined,
              );
            if (
              field.kind === 'decimal' &&
              !/^\d{1,18}(\.\d{1,8})?$/.test(String(value))
            )
              return callback(new Error(c('validation.decimal')));
            if (
              field.kind === 'decimal' &&
              (String(value).split('.')[1]?.length ?? 0) >
                (field.precision ?? 8)
            )
              return callback(new Error(c('validation.precision')));
            if (field.kind === 'number' && !Number.isSafeInteger(Number(value)))
              return callback(new Error(c('validation.integer')));
            if (
              (field.kind === 'number' || field.kind === 'decimal') &&
              ((field.min !== undefined && Number(value) < field.min) ||
                (field.max !== undefined && Number(value) > field.max))
            )
              return callback(new Error(c('validation.range')));
            if (
              field.kind === 'password' &&
              ([...String(value)].length < (field.minLength ?? 6) ||
                String(value).length > 64 ||
                new TextEncoder().encode(String(value)).length > 72)
            )
              return callback(
                new Error(c(field.validationKey || 'v8.passwordRule')),
              );
            if (
              (field.kind === 'text' || !field.kind) &&
              String(value).trim().length === 0
            )
              return callback(new Error(c('validation.required')));
            callback();
          },
        },
      ],
    ]),
  ),
);
async function validate() {
  return (
    formRef.value
      ?.validate()
      .then(() => true)
      .catch(() => false) ?? false
  );
}
defineExpose({ validate });
</script>

<template>
  <ElForm
    ref="formRef"
    class="compute-business-form"
    :model="model"
    :rules="rules"
    :disabled="disabled"
    label-position="top"
    @submit.prevent
  >
    <div class="compute-form-grid">
      <template v-for="field in fields" :key="field.key">
        <ElDivider
          v-if="field.group"
          class="compute-wide"
          content-position="left"
        >
          {{ c(`group.${field.group}`) }}
        </ElDivider>
        <ElFormItem
          :prop="field.key"
          :label="fieldLabel(field)"
          :class="{ 'compute-wide': field.kind === 'textarea' }"
        >
          <div class="compute-field" role="group" :aria-label="fieldLabel(field)" :aria-describedby="field.hint ? `${formId}-${field.key}-help` : undefined">
            <ElSwitch
              v-if="field.kind === 'boolean'"
              v-model="model[field.key]"
              :aria-label="fieldLabel(field)"
              :active-text="c('common.enabled')"
              :disabled="field.disabled"
              :inactive-text="c('common.disabled')"
            />
            <ElInputNumber
              v-else-if="field.kind === 'number'"
              v-model="model[field.key]"
              :aria-label="fieldLabel(field)"
              :min="field.min ?? 0"
              :disabled="field.disabled"
              :max="field.max ?? Number.MAX_SAFE_INTEGER"
              :precision="0"
              controls-position="right"
            />
            <ElSelect
              v-else-if="
                field.kind === 'select' || field.kind === 'multiselect'
              "
              v-model="model[field.key]"
              :aria-label="fieldLabel(field)"
              :multiple="field.kind === 'multiselect'"
              :disabled="field.disabled"
              :clearable="!field.required"
              filterable
              :placeholder="c('common.select')"
            >
              <ElOption
                v-for="option in field.options ??
                field.values?.map((value) => ({
                  value,
                  label: enumLabel(value),
                })) ??
                []"
                :key="option.value"
                :value="option.value"
                :label="option.label"
              />
            </ElSelect>
            <ElDatePicker
              v-else-if="field.kind === 'datetime'"
              v-model="model[field.key]"
              :aria-label="fieldLabel(field)"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              :disabled="field.disabled"
              :placeholder="c('common.select')"
            />
            <ElTimePicker
              v-else-if="field.kind === 'time'"
              v-model="model[field.key]"
              :aria-label="fieldLabel(field)"
              value-format="HH:mm:ss"
              format="HH:mm:ss"
              clearable
              :placeholder="c('common.optional')"
            />
            <ElInput
              v-else
              v-model="model[field.key]"
              :aria-label="fieldLabel(field)"
              :disabled="field.disabled"
              :type="
                field.kind === 'textarea'
                  ? 'textarea'
                  : field.kind === 'password'
                    ? 'password'
                    : 'text'
              "
              :show-password="field.kind === 'password'"
              :maxlength="
                field.maxLength ??
                (field.kind === 'textarea'
                  ? 4000
                  : field.kind === 'password'
                    ? 64
                    : 255)
              "
              :rows="3"
              :inputmode="field.kind === 'decimal' ? 'decimal' : 'text'"
              :autocomplete="field.kind === 'password' ? 'new-password' : 'off'"
              :placeholder="
                c(field.required ? 'common.required' : 'common.optional')
              "
            />
            <p v-if="field.hint" :id="`${formId}-${field.key}-help`" class="compute-help">
              {{ c(`hint.${field.hint}`) }}
            </p>
          </div>
        </ElFormItem>
      </template>
    </div>
  </ElForm>
</template>
