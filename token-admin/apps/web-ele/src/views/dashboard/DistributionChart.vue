<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElEmpty } from 'element-plus';
import { EchartsUI, type EchartsUIType, useEcharts } from '@vben/plugins/echarts';
import { useCompute } from '#/components/business/shared';

const props = withDefaults(defineProps<{
  items: { name: string; value: number; display?: string }[];
  mode?: 'bar' | 'donut';
  unit?: string;
}>(), { mode: 'bar', unit: '' });
const { c } = useCompute();
const chart = ref<EchartsUIType>();
const { isActive, renderEcharts } = useEcharts(chart);
const values = computed(() => props.items.filter(item => Number.isFinite(item.value) && item.value > 0));
watch(() => [isActive.value, values.value, props.mode, props.unit], () => {
  if (!isActive.value || !values.value.length) return;
  const items = values.value;
  const donut = props.mode === 'donut';
  renderEcharts({
    animationDuration: 250,
    color: ['#2563eb', '#14b8a6', '#8b5cf6', '#f59e0b', '#f43f5e', '#06b6d4'],
    tooltip: {
      trigger: 'item', renderMode: 'richText', confine: true,
      formatter: (item: any) => {
        const value = items[item.dataIndex];
        return value ? `${value.name}\n${value.display ?? value.value} ${props.unit}${donut ? ` · ${item.percent}%` : ''}` : '';
      },
    },
    legend: donut ? { bottom: 0, left: 0, right: 0, type: 'scroll', icon: 'circle' } : undefined,
    grid: donut ? undefined : { left: 12, right: 40, top: 12, bottom: 20, containLabel: true },
    xAxis: donut ? undefined : { type: 'value', splitLine: { lineStyle: { type: 'dashed', opacity: .35 } } },
    yAxis: donut ? undefined : { type: 'category', inverse: true, data: items.map(item => item.name), axisTick: { show: false }, axisLine: { show: false }, axisLabel: { width: 110, overflow: 'truncate' } },
    series: [donut ? {
      type: 'pie', radius: ['48%', '72%'], center: ['50%', '44%'],
      avoidLabelOverlap: true, label: { show: false },
      emphasis: { label: { show: true, formatter: '{d}%', fontSize: 22, fontWeight: 'bold' } },
      itemStyle: { borderRadius: 5, borderWidth: 3 },
      data: items,
    } : {
      type: 'bar', barMaxWidth: 24,
      itemStyle: { borderRadius: [0, 5, 5, 0], color: '#2563eb' },
      data: items.map(item => item.value),
    }],
  });
}, { immediate: true, deep: true, flush: 'post' });
</script>
<template>
  <EchartsUI v-show="values.length" ref="chart" class="distribution-chart" />
  <ElEmpty v-if="!values.length" :description="c('common.empty')" :image-size="70" class="distribution-empty" />
</template>
<style scoped>
.distribution-chart { width: 100%; min-width: 0; height: 300px; }
.distribution-empty { min-width: 0; min-height: 300px; }
</style>
