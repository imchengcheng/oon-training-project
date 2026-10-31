<script setup>
import { ref, onMounted } from 'vue'
import { api, query } from '../api'
import Icon from './Icon.vue'
const props = defineProps({ targetId: { type: String, required: true } })
const favoriteId = ref(null), busy = ref(true), error = ref('')
onMounted(async () => {
  try { favoriteId.value = (await api(`/student/campus/favorites/lookup?${query({ targetType: 'ANSWER', targetId: props.targetId })}`)).favoriteId }
  catch { /* A later click can still make an idempotent save. */ }
  finally { busy.value = false }
})
async function toggle() {
  busy.value = true; error.value = ''
  try {
    if (favoriteId.value) { await api(`/student/campus/favorites/${favoriteId.value}`, { method: 'DELETE' }); favoriteId.value = null }
    else favoriteId.value = (await api('/student/campus/favorites', { method: 'PUT', body: { targetType: 'ANSWER', targetId: props.targetId } })).favoriteId
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>
<template>
  <span class="answer-bookmark"><button type="button" class="text-button" :disabled="busy" :aria-pressed="Boolean(favoriteId)" :aria-label="favoriteId ? '取消收藏回答' : '收藏回答'" @click="toggle"><Icon :name="favoriteId ? 'check' : 'book'" :size="14" />{{ favoriteId ? '已收藏' : '收藏' }}</button><small v-if="error" role="alert">{{ error }}</small></span>
</template>
<style scoped>
.answer-bookmark{display:inline-flex;align-items:center;gap:6px}.answer-bookmark small{color:#ac514a;max-width:230px}
</style>
