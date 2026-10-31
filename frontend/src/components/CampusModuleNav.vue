<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({ admin: Boolean })
const emit = defineEmits(['navigate'])
const modules = computed(() => props.admin ? [
  { key: 'guides', title: '指南管理', icon: 'book' },
  { key: 'faqs', title: '常见问题管理', icon: 'chat' },
  { key: 'tickets', title: '工单处理', icon: 'feedback' },
  { key: 'users', title: '用户管理', icon: 'shield' },
  { key: 'gaps', title: '知识缺口分析', icon: 'chart' }
] : [
  { key: 'library', title: '规章资料库', icon: 'book' },
  { key: 'favorites', title: '我的收藏', icon: 'like' },
  { key: 'guides', title: '办事指南', icon: 'file' },
  { key: 'faqs', title: '常见问题', icon: 'chat' },
  { key: 'tickets', title: '咨询工单', icon: 'feedback' },
  { key: 'history', title: '问答记录', icon: 'clock' }
])
</script>

<template>
  <section class="campus-module-nav">
    <div class="campus-module-label">{{ admin ? '校园服务管理' : '校园服务' }}</div>
    <nav :aria-label="admin ? '校园服务管理模块' : '校园服务模块'">
      <router-link v-for="item in modules" :key="item.key" :to="`${admin ? '/admin' : '/student'}/campus/${item.key}`" @click="emit('navigate')"><Icon :name="item.icon" :size="17" /><span>{{ item.title }}</span><Icon name="chevron" :size="12" /></router-link>
    </nav>
  </section>
</template>

<style scoped>
.campus-module-nav{flex-shrink:0;margin:18px 0 5px}.campus-module-label{font-size:10px;letter-spacing:1px;color:#a1ad98;padding:0 13px;margin:0 0 9px}.campus-module-nav nav{display:flex;flex-direction:column;gap:3px}.campus-module-nav a{display:flex;align-items:center;gap:10px;color:#84977a;text-decoration:none;font-size:11px;line-height:1.5;padding:9px 12px;border-radius:7px;min-height:34px}.campus-module-nav a>svg:first-child{flex-shrink:0;color:#91a786}.campus-module-nav a>span{flex:1}.campus-module-nav a>svg:last-child{color:#b7c5ac;flex-shrink:0}.campus-module-nav a:hover{background:#f3f7ef;color:#557f46}.campus-module-nav a.router-link-active{background:#edf4e9;color:#507846;font-weight:500}@media(max-width:900px){.campus-module-nav a{padding:8px 10px;font-size:10px;min-height:32px}.campus-module-label{font-size:9px;padding-left:10px}}@media(max-width:640px){.campus-module-nav a{font-size:11px;padding:9px 12px;min-height:34px}}
</style>
