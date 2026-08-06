<script setup lang="ts">
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import type { EChartsOption } from 'echarts'
import type { DashboardDailyOperations } from '~/types/dashboard'

const props = defineProps<{
  daily: DashboardDailyOperations[]
}>()

use([CanvasRenderer, LineChart, GridComponent, LegendComponent, TooltipComponent])

const option = computed<EChartsOption>(() => ({
  animationDuration: 300,
  color: ['#2563eb', '#0f9f6e', '#d97706'],
  grid: { left: 48, right: 16, top: 48, bottom: 36 },
  legend: {
    top: 0,
    left: 0,
    itemWidth: 18,
    itemHeight: 3,
    textStyle: { color: '#52525b', fontSize: 12 },
  },
  tooltip: {
    trigger: 'axis',
    backgroundColor: '#18181b',
    borderWidth: 0,
    textStyle: { color: '#fafafa' },
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: props.daily.map(item => item.date.slice(5)),
    axisLine: { lineStyle: { color: '#e4e4e7' } },
    axisTick: { show: false },
    axisLabel: { color: '#71717a', hideOverlap: true },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    axisLabel: { color: '#71717a' },
    splitLine: { lineStyle: { color: '#f1f1f3' } },
  },
  series: [
    {
      name: '订单',
      type: 'line',
      smooth: 0.25,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2 },
      areaStyle: { color: 'rgba(37, 99, 235, 0.08)' },
      data: props.daily.map(item => item.orders),
    },
    {
      name: '已支付',
      type: 'line',
      smooth: 0.25,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2 },
      data: props.daily.map(item => item.paid_orders),
    },
    {
      name: '新客户',
      type: 'line',
      smooth: 0.25,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2 },
      data: props.daily.map(item => item.new_customers),
    },
  ],
}))
</script>

<template>
  <ClientOnly>
    <VChart class="trend-chart" :option="option" autoresize />
    <template #fallback>
      <div class="trend-chart chart-loading" />
    </template>
  </ClientOnly>
</template>

<style scoped>
.trend-chart {
  width: 100%;
  height: 300px;
}

.chart-loading {
  background: #fafafa;
}
</style>
