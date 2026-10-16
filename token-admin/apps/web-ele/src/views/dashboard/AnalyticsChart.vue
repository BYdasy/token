<script setup lang="ts">
import { ref, watch } from 'vue';
import {
  EchartsUI,
  type EchartsUIType,
  useEcharts,
} from '@vben/plugins/echarts';
import type { ComputeRecord } from '#/api/compute';

const props = defineProps<{
  rows: ComputeRecord[];
  series: { key: string; name: string; color: string; type?: 'bar' | 'line' }[];
}>();
const chart = ref<EchartsUIType>();
const { isActive, renderEcharts } = useEcharts(chart);
watch(
  () => [isActive.value, props.rows, props.series],
  () => {
    if (!isActive.value) return;
    renderEcharts({
      animation: false,
      grid: { left: 8, right: 12, top: 48, bottom: props.rows.length > 35 ? 38 : 16, containLabel: true },
      legend: { top: 0, left: 0, right: 0, type: 'scroll', icon: 'roundRect', itemWidth: 14, itemHeight: 3 },
      tooltip: {
        trigger: 'axis',
        renderMode: 'richText',
        confine: true,
        formatter: (parameters: any) => {
          const item = Array.isArray(parameters) ? parameters[0] : parameters;
          const row = props.rows[item?.dataIndex];
          return row
            ? [
                row.day,
                ...props.series.map(
                  (series) => `${series.name}: ${row[series.key] ?? 0}`,
                ),
              ].join('\n')
            : '';
        },
      },
      xAxis: {
        type: 'category',
        data: props.rows.map((row) => row.day),
        boundaryGap: true,
        axisLabel: { hideOverlap: true },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { type: 'dashed', opacity: .35 } },
        minInterval: props.series.some((item) => item.key.includes('Members'))
          ? 1
          : undefined,
      },
      dataZoom:
        props.rows.length > 35
          ? [{ type: 'inside' }, { type: 'slider', height: 14, bottom: 0 }]
          : [],
      series: props.series.map((item) => ({
        name: item.name,
        type: item.type || 'line',
        smooth: false,
        showSymbol: props.rows.length < 35,
        data: props.rows.map((row) => Number(row[item.key] || 0)),
        itemStyle: { color: item.color },
        barMaxWidth: 32,
      })),
    });
  },
  { immediate: true, deep: true },
);
</script>
<template>
  <EchartsUI ref="chart" class="analytics-chart" />
</template>
<style scoped>
.analytics-chart { width: 100%; min-width: 0; height: 340px; }
@media (max-width: 520px) { .analytics-chart { height: 300px; } }
</style>
