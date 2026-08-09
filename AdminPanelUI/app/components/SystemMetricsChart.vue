<script setup lang="ts">
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import type { EChartsOption } from 'echarts'
import type { SystemMetricSample } from '~/types/system-status'

const props = defineProps<{
  samples: SystemMetricSample[]
}>()

use([CanvasRenderer, LineChart, GridComponent, LegendComponent, TooltipComponent])

const option = computed<EChartsOption>(() => ({
  animationDuration: 250,
  color: ['#2563eb', '#0891b2', '#0f9f6e'],
  grid: { left: 46, right: 18, top: 46, bottom: 34 },
  legend: {
    top: 0,
    left: 0,
    itemWidth: 18,
    itemHeight: 3,
    textStyle: { color: '#52525b', fontSize: 12 },
  },
  tooltip: {
    trigger: 'axis',
    valueFormatter: value => value === null || value === undefined ? '--' : `${Number(value).toFixed(1)}%`,
    backgroundColor: '#18181b',
    borderWidth: 0,
    textStyle: { color: '#fafafa' },
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: props.samples.map(sample => sample.captured_at.slice(11, 19)),
    axisLine: { lineStyle: { color: '#e4e4e7' } },
    axisTick: { show: false },
    axisLabel: { color: '#71717a', hideOverlap: true },
  },
  yAxis: {
    type: 'value',
    min: 0,
    max: 100,
    axisLabel: { color: '#71717a', formatter: '{value}%' },
    splitLine: { lineStyle: { color: '#f1f1f3' } },
  },
  series: [
    {
      name: '进程 CPU',
      type: 'line',
      smooth: 0.2,
      showSymbol: props.samples.length < 3,
      symbolSize: 5,
      lineStyle: { width: 2 },
      data: props.samples.map(sample => sample.process_cpu_percent),
    },
    {
      name: '系统 CPU',
      type: 'line',
      smooth: 0.2,
      showSymbol: props.samples.length < 3,
      symbolSize: 5,
      lineStyle: { width: 2 },
      data: props.samples.map(sample => sample.system_cpu_percent),
    },
    {
      name: 'JVM 堆内存',
      type: 'line',
      smooth: 0.2,
      showSymbol: props.samples.length < 3,
      symbolSize: 5,
      lineStyle: { width: 2 },
      areaStyle: { color: 'rgba(15, 159, 110, 0.07)' },
      data: props.samples.map(sample => sample.heap_usage_percent),
    },
  ],
}))
</script>

<template>
  <ClientOnly>
    <VChart class="metrics-chart" :option="option" autoresize />
    <template #fallback>
      <div class="metrics-chart chart-loading" />
    </template>
  </ClientOnly>
</template>

<style scoped>
.metrics-chart {
  width: 100%;
  height: 280px;
}

.chart-loading {
  background: #fafafa;
}
</style>
