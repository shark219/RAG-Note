<template>
  <div v-if="visible" class="token-usage" :class="{ 'token-warning': percentage > 80, 'token-danger': percentage > 95 }">
    <div class="token-bar">
      <div class="token-fill" :style="{ width: percentage + '%' }"></div>
    </div>
    <span class="token-text">{{ formatTokens(used) }} / {{ formatTokens(max) }}</span>
  </div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  used: { type: Number, default: 0 },
  max: { type: Number, default: 32000 },
  visible: { type: Boolean, default: true }
});

const percentage = computed(() => {
  if (props.max <= 0) return 0;
  return Math.min(100, Math.round((props.used / props.max) * 100));
});

const formatTokens = (n) => {
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
  return n.toString();
};
</script>

<style scoped>
.token-usage {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  font-size: 11px;
  color: var(--color-text-lightest);
}

.token-bar {
  flex: 1;
  height: 4px;
  background: var(--color-border-light);
  border-radius: 2px;
  overflow: hidden;
  max-width: 120px;
}

.token-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: 2px;
  transition: width 0.3s ease;
}

.token-warning .token-fill {
  background: #e6a23c;
}

.token-danger .token-fill {
  background: #f56c6c;
}

.token-text {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
</style>
