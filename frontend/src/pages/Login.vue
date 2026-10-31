<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { saveAuth } from '../state'
import Icon from '../components/Icon.vue'
const username = ref(''), password = ref(''), busy = ref(false), error = ref('')
const router = useRouter()
async function login() {
  busy.value = true; error.value = ''
  try { const result = await api('/auth/login', { method: 'POST', body: { username: username.value, password: password.value } }); saveAuth(result); router.push(result.user.roleType === 'ADMIN' ? '/admin' : '/student') }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>
<template>
  <main class="login-page">
    <section class="login-story">
      <div class="brand"><span class="brand-mark"><Icon name="book" :size="24" /></span><div>校规知问<small>CAMPUS KNOWLEDGE</small></div></div>
      <div class="story-body"><div class="eyebrow">YOUR CAMPUS, EXPLAINED.</div><h1>校园里的每个疑问，<br>都有据可循。</h1><p>让分散的校园规章成为清晰的答案。<br>从一句提问开始，找到与你相关的每一条规定。</p><div class="story-visual"><div class="visual-ring"></div><Icon name="book" :size="86" /><span class="floating-tag tag-one"><Icon name="check" :size="16" /> 原文可追溯</span><span class="floating-tag tag-two"><Icon name="spark" :size="16" /> 理解你的问题</span></div></div>
      <div class="story-footer"><Icon name="shield" :size="16" /> 基于规章原文 · 回答附有出处</div>
    </section>
    <section class="login-panel"><div class="login-form"><span class="pill">校园规章智能问答系统</span><h2>欢迎回来</h2><p class="muted">登录校园账号，开启有依据的问答</p><form @submit.prevent="login"><label for="username">账号</label><input id="username" v-model="username" autocomplete="username" placeholder="请输入预置账号" required maxlength="64"><label for="password">密码</label><input id="password" v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" required><div v-if="error" class="error" role="alert">{{ error }}</div><button class="primary login-submit" :disabled="busy">{{ busy ? '正在登录…' : '登录系统' }}<Icon name="chevron" :size="18" /></button></form><div class="login-note"><Icon name="lock" :size="16" /><span>账号由学校管理员统一分配。<br>如需帮助，请联系学校相关管理部门。</span></div></div><footer>校规知问 <span>·</span> 让规章更易懂，让办事更从容</footer></section>
  </main>
</template>
