<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, query } from '../api'
import { auth, categories, clearAuth, date } from '../state'
import Icon from '../components/Icon.vue'
import '../campus-hub.css'

const route = useRoute(), router = useRouter()
const isAdmin = computed(() => auth.user?.roleType === 'ADMIN')
const prefix = computed(() => isAdmin.value ? '/admin/campus' : '/student/campus')
const navigation = computed(() => isAdmin.value ? [
  { key: 'guides', title: '指南管理', icon: 'book', lead: '把规章整理成可执行的办事步骤，让同学知道去哪里、带什么。' },
  { key: 'faqs', title: '常见问题管理', icon: 'chat', lead: '维护有制度依据的常见问题，为学生提供更清楚的问答入口。' },
  { key: 'tickets', title: '工单处理', icon: 'feedback', lead: '接收学生咨询，记录处理进展，让未解决的问题得到回应。' },
  { key: 'users', title: '用户管理', icon: 'shield', lead: '管理学生账号与使用状态，维护校园服务的访问权限。' },
  { key: 'gaps', title: '知识缺口分析', icon: 'chart', lead: '从无匹配、证据不足和负反馈中，找到下一份值得补充的知识。' }
] : [
  { key: 'library', title: '规章资料库', icon: 'book', lead: '按分类浏览校园规章，直接查阅原文，让每一次查询都有依据。' },
  { key: 'favorites', title: '我的收藏', icon: 'like', lead: '保存重要规章与有帮助的回答，记录只属于你的阅读笔记。' },
  { key: 'guides', title: '办事指南', icon: 'file', lead: '了解办理材料、具体步骤和受理部门，少一点往返，多一点从容。' },
  { key: 'faqs', title: '常见问题', icon: 'chat', lead: '从大家关心的问题开始，查阅整理答案，也可以继续向 AI 追问。' },
  { key: 'tickets', title: '咨询工单', icon: 'feedback', lead: '没有找到合适的答案？提交具体问题，查看管理员的处理回复。' },
  { key: 'history', title: '问答记录', icon: 'clock', lead: '回到之前的讨论，整理会话标题，或导出一份自己的问答记录。' }
])
const moduleKey = computed(() => navigation.value.some(n => n.key === route.params.module) ? route.params.module : navigation.value[0].key)
const current = computed(() => navigation.value.find(n => n.key === moduleKey.value))
const rows = ref([]), total = ref(0), page = ref(1), size = 12
const keyword = ref(''), category = ref(''), status = ref(''), favoriteType = ref(''), roleType = ref(''), published = ref('')
const loading = ref(false), loadFailed = ref(false), busy = ref(false), error = ref(''), notice = ref(''), showSide = ref(false)
const gaps = ref(null), dialog = ref(''), editing = ref(null), form = ref({}), formError = ref('')
const detail = ref(null), detailLoading = ref(false), dialogNotice = ref(''), sectionKeyword = ref(''), sourceDocs = ref([]), sourceLoading = ref(false)
const ticketStates = { OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决' }
const reasons = { NO_MATCH: '未找到条款', INSUFFICIENT_EVIDENCE: '证据不足', DISLIKE: '回答负反馈' }
let requestSequence = 0, detailSequence = 0, sourceSequence = 0
const paginated = computed(() => moduleKey.value !== 'gaps')
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size)))
const maxGapCount = computed(() => Math.max(1, ...(gaps.value?.items || []).map(i => Number(i.count) || 0)))
const filteredSections = computed(() => {
  const sections = detail.value?.sections || [], term = sectionKeyword.value.trim().toLowerCase()
  return term ? sections.filter(s => [s.sectionLabel, s.text].some(t => String(t || '').toLowerCase().includes(term))) : sections
})
const dialogTitle = computed(() => ({ guide: editing.value ? '编辑办事指南' : '新增办事指南', faq: editing.value ? '编辑常见问题' : '新增常见问题', ticket: '提交咨询工单', reply: '处理咨询工单', user: editing.value ? '编辑用户信息' : '新增学生账号', password: '重置登录密码', rename: '重命名会话', note: '编辑收藏笔记', document: '规章原文', guideDetail: '办事指南详情', ticketDetail: '咨询详情', favoriteDetail: '收藏详情' })[dialog.value] || '详细信息')
const primaryAction = computed(() => isAdmin.value ? ({ guides: '新增指南', faqs: '新增问题', users: '新增学生' })[moduleKey.value] : moduleKey.value === 'tickets' ? '提交咨询' : null)
const isSelf = user => String(user.userId) === String(auth.user?.userId)

function logout() { clearAuth(); router.push('/login') }
function resetAndRefresh() { page.value = 1; refresh() }
function go(key) { showSide.value = false; router.push(`${prefix.value}/${key}`) }
function countLabel() { return ({ library: '份规章', favorites: '条收藏', guides: '份指南', faqs: '个问题', tickets: '条工单', users: '个账号', history: '段会话' })[moduleKey.value] || '条记录' }
function label(categoryCode) { return categories[categoryCode] || categoryCode || '未分类' }
function relatedTitle(item) { return item.sourceTitle || item.sourceDocTitle || '来源文档' }
function questionLink(question) { return { path: '/student', query: { question } } }

async function refresh() {
  const sequence = ++requestSequence, key = moduleKey.value
  loading.value = true; loadFailed.value = false; error.value = ''
  try {
    if (key === 'gaps') {
      const result = await api(`${prefix.value}/gaps?${query({ keyword: keyword.value.trim() })}`)
      if (sequence === requestSequence) { gaps.value = result; rows.value = []; total.value = result.items.length }
    } else {
      const resource = { library: 'documents', history: 'sessions' }[key] || key
      const values = { page: page.value, size, keyword: keyword.value.trim() }
      if (['library', 'guides', 'faqs', 'tickets'].includes(key)) values.category = category.value
      if (['tickets', 'history'].includes(key)) values.status = status.value
      if (key === 'favorites') values.targetType = favoriteType.value
      if (key === 'users') values.roleType = roleType.value
      if (isAdmin.value && ['guides', 'faqs'].includes(key) && published.value !== '') values.published = published.value
      const result = await api(`${prefix.value}/${resource}?${query(values)}`)
      if (sequence === requestSequence) {
        rows.value = result.records; total.value = result.total
        if (page.value > Math.max(1, Math.ceil(result.total / size))) { page.value = Math.max(1, Math.ceil(result.total / size)); await refresh() }
      }
    }
  } catch (e) { if (sequence === requestSequence) { error.value = e.message; loadFailed.value = true } }
  finally { if (sequence === requestSequence) loading.value = false }
}

function clearFilters() { keyword.value = ''; category.value = ''; status.value = ''; favoriteType.value = ''; roleType.value = ''; published.value = ''; resetAndRefresh() }
function changePage(value) { if (value < 1 || value > pageCount.value || loading.value) return; page.value = value; refresh() }
function closeDialog() { if (!busy.value) { dialog.value = ''; detail.value = null; editing.value = null; formError.value = ''; dialogNotice.value = ''; detailLoading.value = false; sourceLoading.value = false; ++detailSequence; ++sourceSequence } }
async function loadSources() {
  const sequence = ++sourceSequence
  sourceLoading.value = true
  try {
    const data = await api('/admin/documents?' + query({ page: 1, size: 100, lifecycleStatus: 'ACTIVE' }))
    if (sequence === sourceSequence) sourceDocs.value = data.records
  } catch (e) { if (sequence === sourceSequence) formError.value = e.message }
  finally { if (sequence === sourceSequence) sourceLoading.value = false }
}
function openEditor(kind, item = null, prefill = '') {
  dialog.value = kind; editing.value = item; formError.value = ''; detail.value = null; dialogNotice.value = ''; detailLoading.value = false
  if (kind === 'guide') {
    const fields = ['title', 'category', 'summary', 'department', 'materials', 'steps', 'processingTime', 'contact', 'sourceDocId', 'published']
    form.value = item ? Object.fromEntries(fields.map(f => [f, item[f] ?? (f === 'published' ? false : '')])) : { title: '', category: 'ACADEMIC_AFFAIRS', summary: '', department: '', materials: '', steps: '', processingTime: '', contact: '', sourceDocId: '', published: false }
    loadSources()
  }
  if (kind === 'faq') {
    const fields = ['question', 'category', 'answer', 'sourceDocId', 'published', 'sortOrder']
    form.value = item ? Object.fromEntries(fields.map(f => [f, item[f] ?? (f === 'published' ? false : f === 'sortOrder' ? 0 : '')])) : { question: prefill.slice(0, 300), category: 'ACADEMIC_AFFAIRS', answer: '', sourceDocId: '', published: false, sortOrder: 0 }
    loadSources()
  }
  if (kind === 'ticket') form.value = { title: '', category: 'ACADEMIC_AFFAIRS', content: '' }
  if (kind === 'reply') form.value = { status: item.status, reply: item.reply || '' }
  if (kind === 'user') form.value = item ? { realName: item.realName || '', enabled: item.enabled } : { username: '', realName: '', studentNo: '', password: '' }
  if (kind === 'password') form.value = { password: '', confirmPassword: '' }
  if (kind === 'rename') form.value = { title: item.title }
  if (kind === 'note') form.value = { note: item.note || '' }
}
function openPrimary() { openEditor(isAdmin.value ? ({ guides: 'guide', faqs: 'faq', users: 'user' })[moduleKey.value] : 'ticket') }
async function showDetails(kind, id) {
  const sequence = ++detailSequence
  dialog.value = kind; detail.value = null; editing.value = null; formError.value = ''; dialogNotice.value = ''; detailLoading.value = true; sectionKeyword.value = ''
  const resource = { document: 'documents', guideDetail: 'guides', ticketDetail: 'tickets' }[kind]
  try { const data = await api(`${prefix.value}/${resource}/${id}`); if (sequence === detailSequence) detail.value = data }
  catch (e) { if (sequence === detailSequence) formError.value = e.message }
  finally { if (sequence === detailSequence) detailLoading.value = false }
}
function openFavorite(item) { dialog.value = 'favoriteDetail'; editing.value = item; detail.value = item; formError.value = ''; dialogNotice.value = ''; detailLoading.value = false }
function viewSource(item) { if (isAdmin.value) router.push('/admin'); else if (item.sourceDocId) showDetails('document', item.sourceDocId) }

async function save() {
  if (busy.value) return
  busy.value = true; formError.value = ''
  const kind = dialog.value, item = editing.value
  try {
    let body = { ...form.value }
    if (kind === 'guide' || kind === 'faq') {
      body.sourceDocId = body.sourceDocId || null
      const resource = kind === 'guide' ? 'guides' : 'faqs', id = item?.[kind === 'guide' ? 'guideId' : 'faqId']
      await api(`${prefix.value}/${resource}${id ? '/' + id : ''}`, { method: id ? 'PUT' : 'POST', body })
    } else if (kind === 'ticket') await api(`${prefix.value}/tickets`, { method: 'POST', body })
    else if (kind === 'reply') await api(`${prefix.value}/tickets/${item.ticketId}`, { method: 'PUT', body })
    else if (kind === 'user') {
      if (!item) validatePassword(body.password)
      await api(`${prefix.value}/users${item ? '/' + item.userId : ''}`, { method: item ? 'PUT' : 'POST', body })
    } else if (kind === 'password') {
      validatePassword(body.password)
      if (body.password !== body.confirmPassword) throw new Error('两次输入的密码不一致。')
      await api(`${prefix.value}/users/${item.userId}/password`, { method: 'PUT', body: { password: body.password } })
    } else if (kind === 'rename') await api(`${prefix.value}/sessions/${item.sessionId}`, { method: 'PUT', body })
    else if (kind === 'note') await api(`${prefix.value}/favorites/${item.favoriteId}`, { method: 'PATCH', body })
    dialog.value = ''; editing.value = null; form.value = {}
    notice.value = { ticket: '咨询已提交，可在工单中查看处理进展。', reply: '处理结果已保存。', password: '新密码已生效，请通过合适的方式告知账号使用者。', note: '收藏笔记已保存。', rename: '会话标题已更新。' }[kind] || '保存成功。'
    await refresh()
  } catch (e) { formError.value = e.message }
  finally { busy.value = false }
}
function validatePassword(value) {
  const length = new TextEncoder().encode(value || '').length
  if (length < 10 || length > 72) throw new Error('密码过短或过长，建议使用10—32位英文字母、数字与符号。')
}
async function favoriteDocument(doc) {
  if (busy.value) return
  busy.value = true; error.value = ''; formError.value = ''
  try { await api(`${prefix.value}/favorites`, { method: 'PUT', body: { targetType: 'DOCUMENT', targetId: doc.docId } }); notice.value = '已加入我的收藏，可在收藏中添加阅读笔记。'; if (dialog.value) dialogNotice.value = notice.value }
  catch (e) { if (dialog.value) formError.value = e.message; else error.value = e.message }
  finally { busy.value = false }
}
async function removeFavorite(item) {
  if (!confirm('确认移除此条收藏？原文与问答记录会保留。')) return
  busy.value = true; error.value = ''
  try { await api(`${prefix.value}/favorites/${item.favoriteId}`, { method: 'DELETE' }); notice.value = '已移除收藏。'; await refresh() }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function askFaq(item) {
  busy.value = true; error.value = ''
  try { await api(`${prefix.value}/faqs/${item.faqId}/use`, { method: 'POST' }); router.push(questionLink(item.question)) }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function togglePublished(kind, item) {
  if (!confirm(`确认${item.published ? '撤回发布' : '发布'}这${kind === 'guide' ? '份指南' : '个问题'}？${!item.published ? '发布内容需要关联当前有效的规章来源。' : ''}`)) return
  busy.value = true; error.value = ''
  const fields = kind === 'guide' ? ['title', 'category', 'summary', 'department', 'materials', 'steps', 'processingTime', 'contact', 'sourceDocId'] : ['question', 'category', 'answer', 'sourceDocId', 'sortOrder']
  const body = Object.fromEntries(fields.map(f => [f, item[f] ?? (f === 'sortOrder' ? 0 : '')]))
  body.sourceDocId ||= null; body.published = !item.published
  try { await api(`${prefix.value}/${kind === 'guide' ? 'guides' : 'faqs'}/${item[kind === 'guide' ? 'guideId' : 'faqId']}`, { method: 'PUT', body }); notice.value = body.published ? '已发布。' : '已撤回发布，草稿仍保留。'; await refresh() }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function toggleUser(item) {
  if (isSelf(item) || !confirm(`确认${item.enabled ? '禁用' : '启用'}账号“${item.username}”？`)) return
  busy.value = true; error.value = ''
  try { await api(`${prefix.value}/users/${item.userId}`, { method: 'PUT', body: { realName: item.realName, enabled: !item.enabled } }); notice.value = `账号已${item.enabled ? '禁用' : '启用'}。`; await refresh() }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
function downloadFile(filename, content, type) {
  const url = URL.createObjectURL(new Blob([content], { type })), a = document.createElement('a')
  a.href = url; a.download = filename; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function exportSession(item) {
  busy.value = true; error.value = ''
  try { const result = await api(`${prefix.value}/sessions/${item.sessionId}/export`); downloadFile(result.filename, result.markdown, 'text/markdown;charset=utf-8'); notice.value = '问答记录已导出。' }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function supplementFaq(item) { await router.push(`${prefix.value}/faqs`); openEditor('faq', null, item.question) }
watch(moduleKey, () => {
  ++requestSequence; page.value = 1; keyword.value = ''; category.value = ''; status.value = ''; favoriteType.value = ''; roleType.value = ''; published.value = ''; rows.value = []; total.value = 0; gaps.value = null; notice.value = ''; error.value = ''; showSide.value = false
  if (!busy.value) closeDialog()
  refresh()
}, { immediate: true })
watch(() => [moduleKey.value, route.query.message, route.query.question], () => {
  const message = String(route.query.message || '')
  if (!isAdmin.value && moduleKey.value === 'tickets' && /^[1-9][0-9]*$/.test(message)) {
    openEditor('ticket')
    form.value.relatedMessageId = message
    form.value.title = String(route.query.question || '').slice(0, 100)
  }
}, { immediate: true })
</script>

<template>
  <div class="workspace hub-workspace">
    <button v-if="showSide" class="mobile-scrim" aria-label="关闭导航" @click="showSide = false"></button>
    <aside class="sidebar hub-sidebar" :class="{ 'side-open': showSide }">
      <div class="brand"><span class="brand-mark"><Icon name="book" :size="23" /></span><div>校规知问<small>CAMPUS SERVICES</small></div></div>
      <div class="side-label admin-side-label">{{ isAdmin ? '校园服务管理' : '校园服务' }}</div>
      <nav class="admin-nav hub-nav" aria-label="校园服务模块"><router-link v-for="n in navigation" :key="n.key" :to="`${prefix}/${n.key}`" :class="{ active: moduleKey === n.key }" @click="showSide = false"><Icon :name="n.icon" :size="19" />{{ n.title }}</router-link></nav>
      <div class="hub-nav-divider"></div>
      <nav class="admin-nav hub-return"><router-link :to="isAdmin ? '/admin' : '/student'"><Icon :name="isAdmin ? 'search' : 'spark'" :size="19" />{{ isAdmin ? '规章知识库' : '规章智能问答' }}<Icon name="chevron" :size="14" /></router-link><template v-if="isAdmin"><router-link to="/admin/monitor"><Icon name="chart" :size="19" />运行概览</router-link><router-link to="/admin/feedback"><Icon name="feedback" :size="19" />学生反馈</router-link></template></nav>
      <div class="side-tip"><Icon name="shield" :size="19" /><strong>每一份服务，都有依据</strong><p>{{ isAdmin ? '从规章维护到咨询回应，\n持续完善校园知识服务。' : '查规章、看指南、留记录，\n把校园问题放在这里解决。' }}</p></div>
      <div class="user-card"><div class="avatar">{{ auth.user?.realName?.slice(0, 1) }}</div><div><strong>{{ auth.user?.realName }}</strong><small>{{ isAdmin ? '系统管理员' : `学生 · ${auth.user?.studentNo || ''}` }}</small></div><button class="icon-button" aria-label="退出登录" @click="logout"><Icon name="logout" :size="18" /></button></div>
    </aside>
    <main class="main-panel">
      <header class="topbar"><div class="header-title"><button class="icon-button mobile-menu" aria-label="打开导航" @click="showSide = !showSide"><Icon name="menu" /></button><span class="muted">{{ isAdmin ? '校园服务管理' : '校园服务' }}</span><Icon name="chevron" :size="14" /><span>{{ current.title }}</span></div><div class="header-actions"><span class="status-dot"></span><span class="small muted">有问有据，服务有回应</span></div></header>
      <div class="hub-content">
        <div class="hub-heading"><div><div class="eyebrow">{{ isAdmin ? 'CAMPUS SERVICE WORKSPACE' : 'CAMPUS KNOWLEDGE & SERVICES' }}</div><h1>{{ current.title }}</h1><p>{{ current.lead }}</p></div><div class="hub-heading-actions"><button class="secondary" :disabled="loading || busy" aria-label="刷新校园服务数据" @click="refresh"><Icon name="refresh" :size="16" />刷新</button><button v-if="primaryAction" class="primary" :disabled="busy" @click="openPrimary"><Icon name="plus" :size="16" />{{ primaryAction }}</button></div></div>
        <div v-if="error" class="error" role="alert">{{ error }}<button class="icon-button" aria-label="关闭错误" @click="error = ''"><Icon name="close" :size="16" /></button></div>
        <div v-if="notice" class="success-notice" role="status"><Icon name="check" :size="16" />{{ notice }}<button class="icon-button" aria-label="关闭通知" @click="notice = ''"><Icon name="close" :size="16" /></button></div>
        <div v-if="moduleKey === 'library'" class="hub-category-grid"><button v-for="(name, code) in categories" :key="code" :class="['hub-category-card', { active: category === code }]" @click="category = category === code ? '' : code; resetAndRefresh()"><span><Icon :name="({ STUDENT_STATUS: 'book', DISCIPLINE: 'shield', ACADEMIC_AFFAIRS: 'file', DORMITORY: 'spark' })[code]" :size="21" /></span><div><strong>{{ name }}</strong><small>{{ ({ STUDENT_STATUS: '学籍与学习资格', DISCIPLINE: '行为规范与奖惩', ACADEMIC_AFFAIRS: '教学管理与办事', DORMITORY: '住宿与校园生活' })[code] }}</small></div><Icon name="chevron" :size="14" /></button></div>
        <div v-if="moduleKey === 'gaps' && gaps" class="hub-kpi-grid"><article v-for="k in [{ label: '未找到条款', value: gaps.noMatchCount, icon: 'search' }, { label: '证据不足', value: gaps.insufficientCount, icon: 'file' }, { label: '回答负反馈', value: gaps.dislikeCount, icon: 'dislike' }, { label: '待处理咨询', value: gaps.openTicketCount, icon: 'feedback' }]" :key="k.label" class="hub-kpi"><div><span>{{ k.label }}</span><Icon :name="k.icon" :size="18" /></div><strong>{{ k.value }}</strong><small>根据当前真实记录汇总</small></article></div>
        <section class="hub-panel">
          <form class="hub-toolbar" @submit.prevent="resetAndRefresh"><div class="hub-search"><Icon name="search" :size="17" /><input v-model="keyword" aria-label="搜索校园服务记录" :placeholder="({ library: '搜索规章名称或正文', favorites: '搜索收藏标题或笔记', guides: '搜索指南或受理部门', faqs: '搜索常见问题', tickets: '搜索咨询标题或内容', users: '搜索姓名、账号或学号', history: '搜索会话标题', gaps: '搜索待补充的问题' })[moduleKey]" /></div><select v-if="['library', 'guides', 'faqs', 'tickets'].includes(moduleKey)" v-model="category" aria-label="筛选规章分类" @change="resetAndRefresh"><option value="">全部分类</option><option v-for="(name, code) in categories" :key="code" :value="code">{{ name }}</option></select><select v-if="moduleKey === 'favorites'" v-model="favoriteType" aria-label="筛选收藏类型" @change="resetAndRefresh"><option value="">全部收藏</option><option value="DOCUMENT">规章文档</option><option value="ANSWER">问答回答</option></select><select v-if="['tickets', 'history'].includes(moduleKey)" v-model="status" aria-label="筛选处理状态" @change="resetAndRefresh"><option value="">全部状态</option><template v-if="moduleKey === 'tickets'"><option v-for="(name, code) in ticketStates" :key="code" :value="code">{{ name }}</option></template><template v-else><option value="ACTIVE">进行中</option><option value="CLOSED">已关闭</option></template></select><select v-if="moduleKey === 'users'" v-model="roleType" aria-label="筛选用户角色" @change="resetAndRefresh"><option value="">全部角色</option><option value="STUDENT">学生</option><option value="ADMIN">管理员</option></select><select v-if="isAdmin && ['guides', 'faqs'].includes(moduleKey)" v-model="published" aria-label="筛选发布状态" @change="resetAndRefresh"><option value="">全部状态</option><option value="true">已发布</option><option value="false">草稿</option></select><button class="secondary" :disabled="loading">搜索</button><button v-if="keyword || category || status || favoriteType || roleType || published" type="button" class="text-button" @click="clearFilters">重置</button><span class="hub-record-count">共 {{ total }} {{ countLabel() }}</span></form>
          <div v-if="loading" class="hub-loading" role="status"><span class="spinner"></span>正在加载…</div><div v-else-if="loadFailed" class="hub-empty"><Icon name="refresh" :size="36" /><h3>本次加载未完成</h3><p>请根据页面提示检查后重试，或稍后再来查看。</p><button class="secondary" @click="refresh">重新加载</button></div>
          <template v-else-if="moduleKey === 'library'">
            <div v-if="rows.length" class="hub-document-grid"><article v-for="d in rows" :key="d.docId" class="hub-document-card"><div class="hub-document-top"><span class="hub-file-symbol"><Icon name="file" :size="24" /><small>{{ d.fileType }}</small></span><span class="hub-badge">{{ label(d.category) }}</span></div><button class="hub-title-button" @click="showDetails('document', d.docId)"><h2>{{ d.title }}</h2></button><p class="hub-card-meta">{{ d.chunkCount }} 个正文片段 · {{ date(d.uploadTime) }}</p><div class="hub-card-actions"><button class="text-button" @click="showDetails('document', d.docId)">查阅原文<Icon name="chevron" :size="13" /></button><button class="text-button" :disabled="busy" @click="favoriteDocument(d)"><Icon name="like" :size="14" />收藏</button></div></article></div>
            <div v-else class="hub-empty"><Icon name="book" :size="38" /><h3>暂时没有可查阅的规章</h3><p>管理员发布有效规章后，正文会出现在这里。你也可以先描述问题，尝试智能问答。</p><router-link class="secondary" to="/student">前往智能问答</router-link></div>
          </template>
          <template v-else-if="moduleKey === 'favorites'">
            <div v-if="rows.length" class="hub-record-list"><article v-for="f in rows" :key="f.favoriteId" class="hub-favorite"><div class="hub-record-top"><span class="hub-badge">{{ f.targetType === 'DOCUMENT' ? '规章文档' : '问答回答' }}</span><span v-if="!f.available" class="hub-badge hub-badge-warning">原内容已不可用</span><small>{{ date(f.createTime) }}</small></div><button class="hub-title-button" @click="f.targetType === 'DOCUMENT' && f.available ? showDetails('document', f.targetId) : openFavorite(f)"><h2>{{ f.title }}</h2></button><p class="hub-preview">{{ f.preview || '暂无内容预览。' }}</p><div v-if="f.note" class="hub-reading-note"><strong>我的笔记</strong>{{ f.note }}</div><div class="hub-card-actions"><div><button class="text-button" :disabled="busy" @click="openEditor('note', f)">{{ f.note ? '编辑笔记' : '添加笔记' }}</button><router-link v-if="f.targetType === 'ANSWER' && f.available && f.sessionId" class="text-button" :to="{ path: '/student', query: { session: f.sessionId } }">回到会话</router-link><button v-if="f.targetType === 'DOCUMENT' && f.available" class="text-button" @click="showDetails('document', f.targetId)">查阅原文</button></div><button class="text-button danger" :disabled="busy" @click="removeFavorite(f)">移除收藏</button></div></article></div>
            <div v-else class="hub-empty"><Icon name="like" :size="38" /><h3>把重要内容留在身边</h3><p>在资料库收藏规章，或在智能问答中收藏回答，就能在这里再次找到它们。</p><button class="secondary" @click="go('library')">去资料库看看</button></div>
          </template>
          <template v-else-if="moduleKey === 'guides'">
            <div v-if="rows.length" class="hub-guide-grid"><article v-for="g in rows" :key="g.guideId" class="hub-guide-card"><div class="hub-record-top"><span class="hub-badge">{{ label(g.category) }}</span><span v-if="isAdmin" :class="['hub-badge', { 'hub-badge-muted': !g.published }]">{{ g.published ? '已发布' : '草稿' }}</span><span v-if="isAdmin && !g.sourceAvailable" class="hub-badge hub-badge-warning">来源待核验</span></div><button class="hub-title-button" @click="showDetails('guideDetail', g.guideId)"><h2>{{ g.title }}</h2></button><p class="hub-preview">{{ g.summary || '查看完整办理材料与流程。' }}</p><div class="hub-guide-meta"><span>受理部门<strong>{{ g.department || '未填写' }}</strong></span><span>办理时间<strong>{{ g.processingTime || '详见办理说明' }}</strong></span></div><div class="hub-source-line"><Icon name="file" :size="13" /><span>{{ g.sourceDocId ? relatedTitle(g) : '尚未关联来源' }}</span></div><div class="hub-card-actions"><button class="text-button" @click="showDetails('guideDetail', g.guideId)">查看指南<Icon name="chevron" :size="13" /></button><div v-if="isAdmin"><button class="text-button" :disabled="busy" @click="openEditor('guide', g)">编辑</button><button :class="['text-button', { danger: g.published }]" :disabled="busy" @click="togglePublished('guide', g)">{{ g.published ? '撤回' : '发布' }}</button></div></div></article></div>
            <div v-else class="hub-empty"><Icon name="file" :size="38" /><h3>{{ isAdmin ? '把规章变成清楚的办事指南' : '办事指南正在完善中' }}</h3><p>{{ isAdmin ? '关联有效规章，录入真实受理部门、材料和步骤，核对后再发布。' : '指南需要依据真实规章核对后发布。你可以先查阅原文，或提交具体咨询。' }}</p><button class="secondary" @click="isAdmin ? openEditor('guide') : go('library')">{{ isAdmin ? '新增第一份指南' : '查阅规章原文' }}</button></div>
          </template>
          <template v-else-if="moduleKey === 'faqs'">
            <div v-if="rows.length" class="hub-faq-list"><article v-for="f in rows" :key="f.faqId" class="hub-faq"><div class="hub-record-top"><span class="hub-badge">{{ label(f.category) }}</span><span v-if="isAdmin" :class="['hub-badge', { 'hub-badge-muted': !f.published }]">{{ f.published ? '已发布' : '草稿' }}</span><span v-if="isAdmin && !f.sourceAvailable" class="hub-badge hub-badge-warning">来源待核验</span><small>已使用 {{ f.useCount }} 次</small></div><h2><span class="hub-question-mark">Q</span>{{ f.question }}</h2><p class="hub-faq-answer">{{ f.answer }}</p><div class="hub-source-line"><Icon name="shield" :size="13" /><span>人工维护 · {{ f.sourceDocId ? relatedTitle(f) : '尚未关联来源' }}</span></div><div class="hub-card-actions"><button v-if="f.sourceDocId" class="text-button" @click="viewSource(f)">{{ isAdmin ? '查看知识库' : '查阅依据' }}<Icon name="chevron" :size="13" /></button><span v-else></span><div v-if="isAdmin"><button class="text-button" :disabled="busy" @click="openEditor('faq', f)">编辑</button><button :class="['text-button', { danger: f.published }]" :disabled="busy" @click="togglePublished('faq', f)">{{ f.published ? '撤回发布' : '发布' }}</button></div><button v-else class="secondary" :disabled="busy" @click="askFaq(f)"><Icon name="spark" :size="14" />用这个问题咨询 AI</button></div></article></div>
            <div v-else class="hub-empty"><Icon name="chat" :size="38" /><h3>{{ isAdmin ? '从第一个高频问题开始' : '常见问题正在整理中' }}</h3><p>{{ isAdmin ? '依据真实规章维护问题与答案，发布后学生可查看并继续追问。' : '你可以直接提出自己的问题，系统会检索已收录的规章并给出引用。' }}</p><button v-if="isAdmin" class="secondary" @click="openEditor('faq')">新增常见问题</button><router-link v-else class="secondary" to="/student">前往智能问答</router-link></div>
          </template>
          <template v-else-if="moduleKey === 'tickets'">
            <div v-if="rows.length" class="hub-record-list"><article v-for="t in rows" :key="t.ticketId" class="hub-ticket"><div class="hub-record-top"><span :class="['hub-badge', { 'hub-badge-warning': t.status === 'OPEN', 'hub-badge-muted': t.status === 'RESOLVED' }]">{{ ticketStates[t.status] }}</span><span class="hub-badge hub-badge-muted">{{ label(t.category) }}</span><small>{{ date(t.createdAt) }}</small></div><button class="hub-title-button" @click="showDetails('ticketDetail', t.ticketId)"><h2>{{ t.title }}</h2></button><p class="hub-preview">{{ t.content }}</p><div v-if="t.reply" class="hub-ticket-reply"><strong>处理回复 · {{ t.handlerName || '管理员' }}</strong>{{ t.reply }}</div><div class="hub-card-actions"><small v-if="isAdmin" class="hub-meta-text">{{ t.studentName }} · {{ t.studentNo }}</small><small v-else class="hub-meta-text">{{ t.repliedAt ? `最近回复 ${date(t.repliedAt)}` : '提交后请关注处理进展' }}</small><button class="text-button" :disabled="busy" @click="isAdmin ? openEditor('reply', t) : showDetails('ticketDetail', t.ticketId)">{{ isAdmin ? '处理工单' : '查看详情' }}<Icon name="chevron" :size="13" /></button></div></article></div>
            <div v-else class="hub-empty"><Icon name="feedback" :size="38" /><h3>{{ isAdmin ? '当前没有咨询工单' : '遇到问题，我们一起解决' }}</h3><p>{{ isAdmin ? '学生提交咨询后，可以在这里分配处理状态并给出回复。' : '描述问题和已尝试的步骤，管理员会在工单中回复。请勿填写密码等敏感信息。' }}</p><button v-if="!isAdmin" class="secondary" @click="openEditor('ticket')">提交我的咨询</button></div>
          </template>
          <template v-else-if="moduleKey === 'users'">
            <div v-if="rows.length" class="hub-table-wrap"><table class="hub-table"><thead><tr><th>姓名 / 账号</th><th>学号</th><th>角色</th><th>使用状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="u in rows" :key="u.userId"><td><strong>{{ u.realName }}</strong><small>{{ u.username }}<template v-if="isSelf(u)"> · 当前账号</template></small></td><td>{{ u.studentNo || '—' }}</td><td><span class="hub-badge">{{ u.roleType === 'ADMIN' ? '管理员' : '学生' }}</span></td><td><span :class="['hub-badge', { 'hub-badge-muted': !u.enabled }]">{{ u.enabled ? '已启用' : '已禁用' }}</span></td><td>{{ date(u.createdAt) }}</td><td><div class="hub-inline-actions"><button class="text-button" :disabled="busy" @click="openEditor('user', u)">编辑</button><button v-if="u.roleType === 'STUDENT'" class="text-button" :disabled="busy" @click="openEditor('password', u)">重置密码</button><button :class="['text-button', { danger: u.enabled }]" :disabled="busy || isSelf(u)" @click="toggleUser(u)">{{ u.enabled ? '禁用' : '启用' }}</button></div></td></tr></tbody></table></div>
            <div v-else class="hub-empty"><Icon name="shield" :size="38" /><h3>没有符合条件的账号</h3><p>调整搜索条件，或为新同学创建学生账号。</p><button class="secondary" @click="openEditor('user')">新增学生账号</button></div>
          </template>
          <template v-else-if="moduleKey === 'history'">
            <div v-if="rows.length" class="hub-record-list"><article v-for="s in rows" :key="s.sessionId" class="hub-history"><div class="hub-history-symbol"><Icon :name="s.status === 'CLOSED' ? 'lock' : 'chat'" :size="21" /></div><div class="hub-history-body"><div class="hub-record-top"><span :class="['hub-badge', { 'hub-badge-muted': s.status === 'CLOSED' }]">{{ s.status === 'CLOSED' ? '已关闭' : '进行中' }}</span><small>{{ s.messageCount }} 条消息 · 最近活跃 {{ date(s.lastActiveTime || s.createTime) }}</small></div><router-link class="hub-title-button" :to="{ path: '/student', query: { session: s.sessionId } }"><h2>{{ s.title }}</h2></router-link><div class="hub-inline-actions"><router-link class="text-button" :to="{ path: '/student', query: { session: s.sessionId } }">回到会话<Icon name="chevron" :size="13" /></router-link><button class="text-button" :disabled="busy" @click="openEditor('rename', s)">重命名</button><button class="text-button" :disabled="busy" @click="exportSession(s)">导出 Markdown</button></div></div></article></div>
            <div v-else class="hub-empty"><Icon name="clock" :size="38" /><h3>每一次提问，都可以再次找到</h3><p>开启一次智能问答，之后就可以在这里查找、整理和导出你的会话。</p><router-link class="secondary" to="/student">开启智能问答</router-link></div>
          </template>
          <template v-else-if="moduleKey === 'gaps'">
            <div v-if="gaps?.items?.length" class="hub-gaps"><div class="hub-gap-intro"><Icon name="spark" :size="17" /><p>优先处理出现频率较高的问题。补充原始制度可以改善检索；维护 FAQ 则能为常见问题提供清楚的入口。</p><router-link class="secondary" to="/admin">维护知识库</router-link></div><article v-for="(g, i) in gaps.items" :key="`${g.question}-${g.reason}`" class="hub-gap-row"><span class="hub-gap-rank">{{ String(i + 1).padStart(2, '0') }}</span><div class="hub-gap-body"><h2>{{ g.question }}</h2><div class="hub-record-top"><span class="hub-badge hub-badge-warning">{{ reasons[g.reason] || g.reason }}</span><small>最近提问 {{ date(g.lastAskedAt) }}</small></div><div class="hub-gap-bar"><span :style="{ width: `${g.count / maxGapCount * 100}%` }"></span></div></div><div class="hub-gap-action"><strong>{{ g.count }}<small> 次</small></strong><button class="text-button" :disabled="busy" @click="supplementFaq(g)">补充 FAQ<Icon name="plus" :size="13" /></button></div></article></div>
            <div v-else class="hub-empty"><Icon name="chart" :size="38" /><h3>暂未发现待补充的问题</h3><p>实际问答产生无匹配、证据不足或负反馈后，这里会汇总需要关注的知识缺口。</p><router-link class="secondary" to="/admin">查看知识库</router-link></div>
          </template>
          <div v-if="paginated && total > 0 && !loading" class="hub-pagination"><span>共 {{ total }} {{ countLabel() }} · 每页 {{ size }} 条</span><div><button class="secondary" :disabled="page <= 1 || loading" @click="changePage(page - 1)">上一页</button><span>{{ page }} / {{ pageCount }}</span><button class="secondary" :disabled="page >= pageCount || loading" @click="changePage(page + 1)">下一页</button></div></div>
        </section>
        <p class="hub-footer"><Icon name="shield" :size="14" />{{ isAdmin ? '指南与常见问题发布前需要关联有效规章；来源不可用后学生端将停止展示。' : '办事指南与常见问题为人工整理，正式办理仍以学校主管部门的现行要求为准。' }}</p>
      </div>
    </main>

    <div v-if="dialog" class="modal-backdrop" @click.self="closeDialog"><section :class="['modal', 'hub-modal', { 'hub-modal-wide': dialog === 'document' }]" role="dialog" aria-modal="true" :aria-label="dialogTitle"><div class="modal-header"><h2>{{ dialogTitle }}</h2><button type="button" class="icon-button" aria-label="关闭窗口" :disabled="busy" @click="closeDialog"><Icon name="close" /></button></div><div v-if="formError" class="error" role="alert">{{ formError }}</div><div v-if="dialogNotice" class="success-notice" role="status"><Icon name="check" :size="16" />{{ dialogNotice }}</div>
      <div v-if="detailLoading" class="hub-loading"><span class="spinner"></span>正在加载内容…</div>
      <template v-else-if="dialog === 'document' && detail"><h3>{{ detail.title }}</h3><div class="hub-detail-meta"><span class="hub-badge">{{ label(detail.category) }}</span><span>{{ detail.fileType }}</span><span>{{ detail.chunkCount }} 个正文片段</span><span>收录 {{ date(detail.uploadTime) }}</span></div><div class="hub-document-tools"><div class="hub-search"><Icon name="search" :size="16" /><input v-model="sectionKeyword" aria-label="搜索当前规章正文" placeholder="搜索本篇正文或条款" /></div><button class="secondary" :disabled="busy" @click="favoriteDocument(detail)"><Icon name="like" :size="14" />收藏文档</button></div><div class="hub-document-sections"><article v-for="(s, i) in filteredSections" :key="s.chunkId || i"><div><strong>{{ s.sectionLabel || `正文片段 ${i + 1}` }}</strong><span v-if="s.pageStart">PDF 物理页 {{ s.pageStart }}<template v-if="s.pageEnd && s.pageEnd !== s.pageStart">—{{ s.pageEnd }}</template></span></div><p>{{ s.text }}</p></article><p v-if="!filteredSections.length" class="hub-empty-inline">未找到匹配的正文片段，试试其他关键词。</p></div></template>
      <template v-else-if="dialog === 'guideDetail' && detail"><div class="hub-record-top"><span class="hub-badge">{{ label(detail.category) }}</span><span v-if="isAdmin" class="hub-badge hub-badge-muted">{{ detail.published ? '已发布' : '草稿' }}</span></div><h3>{{ detail.title }}</h3><p class="hub-detail-summary">{{ detail.summary }}</p><dl class="hub-detail-grid"><dt>受理部门</dt><dd>{{ detail.department || '未填写' }}</dd><dt>办理时间</dt><dd>{{ detail.processingTime || '详见主管部门通知' }}</dd><dt>联系方式</dt><dd>{{ detail.contact || '未填写' }}</dd></dl><h4 class="hub-section-title">需要准备的材料</h4><p class="hub-detail-text">{{ detail.materials || '暂未填写，请咨询受理部门。' }}</p><h4 class="hub-section-title">办理步骤</h4><p class="hub-detail-text">{{ detail.steps || '暂未填写，请咨询受理部门。' }}</p><div class="hub-source-box"><Icon name="shield" :size="17" /><div><strong>规章依据</strong><p>{{ detail.sourceDocId ? relatedTitle(detail) : '尚未关联来源' }}</p><button v-if="detail.sourceDocId" class="text-button" @click="viewSource(detail)">{{ isAdmin ? '前往知识库核验' : '查阅依据原文' }}<Icon name="chevron" :size="13" /></button></div></div><p class="hub-field-note">本指南由管理员依据规章整理，更新时间 {{ date(detail.updatedAt) }}。正式办理请以受理部门现行要求为准。</p></template>
      <template v-else-if="dialog === 'ticketDetail' && detail"><div class="hub-record-top"><span class="hub-badge">{{ ticketStates[detail.status] }}</span><span class="hub-badge hub-badge-muted">{{ label(detail.category) }}</span></div><h3>{{ detail.title }}</h3><p class="hub-field-note">提交于 {{ date(detail.createdAt) }}<template v-if="isAdmin"> · {{ detail.studentName }} {{ detail.studentNo }}</template></p><div class="hub-detail-text">{{ detail.content }}</div><h4 class="hub-section-title">处理回复</h4><div v-if="detail.reply" class="hub-ticket-reply"><strong>{{ detail.handlerName || '管理员' }} · {{ date(detail.repliedAt) }}</strong>{{ detail.reply }}</div><p v-else class="hub-field-note">暂未收到回复，请稍后查看处理进展。</p><button v-if="isAdmin" class="primary hub-detail-action" @click="openEditor('reply', detail)">处理这条工单</button></template>
      <template v-else-if="dialog === 'favoriteDetail' && detail"><span class="hub-badge">{{ detail.targetType === 'ANSWER' ? '问答回答' : '规章文档' }}</span><h3 class="hub-detail-favorite-title">{{ detail.title }}</h3><div v-if="!detail.available" class="notice">原内容当前不可用，以下保留收藏时的内容预览与笔记。</div><p class="hub-detail-text">{{ detail.preview || '暂无内容预览。' }}</p><div v-if="detail.note" class="hub-reading-note"><strong>我的笔记</strong>{{ detail.note }}</div><div class="hub-inline-actions"><button class="text-button" @click="openEditor('note', detail)">编辑笔记</button><router-link v-if="detail.available && detail.sessionId" class="text-button" :to="{ path: '/student', query: { session: detail.sessionId } }">回到原会话</router-link></div></template>
      <form v-else-if="!['document', 'guideDetail', 'ticketDetail', 'favoriteDetail'].includes(dialog)" @submit.prevent="save">
        <template v-if="dialog === 'guide' || dialog === 'faq'"><label for="hub-title">{{ dialog === 'guide' ? '指南标题' : '常见问题' }}</label><input id="hub-title" v-model.trim="form[dialog === 'guide' ? 'title' : 'question']" :maxlength="dialog === 'guide' ? 140 : 300" required /><div class="hub-form-grid"><div><label for="hub-category">规章分类</label><select id="hub-category" v-model="form.category" required><option v-for="(name, code) in categories" :key="code" :value="code">{{ name }}</option></select></div><div v-if="dialog === 'guide'"><label for="hub-department">受理部门</label><input id="hub-department" v-model.trim="form.department" maxlength="80" /></div><div v-else><label for="hub-sort">排序值（小值优先）</label><input id="hub-sort" v-model.number="form.sortOrder" type="number" min="0" max="100000" step="1" required /></div></div><template v-if="dialog === 'guide'"><label for="hub-summary">简要说明</label><textarea id="hub-summary" v-model="form.summary" maxlength="500" rows="2" placeholder="简述适用情形，不要编造制度中没有的要求。"></textarea><label for="hub-materials">所需材料</label><textarea id="hub-materials" v-model="form.materials" maxlength="4000" rows="4" placeholder="每行一项，依据关联规章填写。"></textarea><label for="hub-steps">办理步骤</label><textarea id="hub-steps" v-model="form.steps" maxlength="10000" rows="6" placeholder="按实际顺序填写办理流程。"></textarea><label for="hub-processing">办理时间 / 时限</label><input id="hub-processing" v-model="form.processingTime" maxlength="200" /><label for="hub-contact">联系与办理方式</label><textarea id="hub-contact" v-model="form.contact" maxlength="500" rows="2"></textarea></template><template v-else><label for="hub-answer">人工整理答案</label><textarea id="hub-answer" v-model="form.answer" maxlength="10000" rows="8" required placeholder="以来源原文为依据，填写可核验的解释。"></textarea></template><label for="hub-source">依据规章{{ form.published ? '（发布必选）' : '（草稿可稍后关联）' }}</label><select id="hub-source" v-model="form.sourceDocId" :required="form.published" :disabled="sourceLoading"><option value="">{{ sourceLoading ? '正在加载有效规章…' : '选择依据规章' }}</option><option v-if="form.sourceDocId && !sourceDocs.some(d => d.docId === form.sourceDocId)" :value="form.sourceDocId">{{ editing?.sourceTitle || '原关联文档（当前不可用）' }}</option><option v-for="d in sourceDocs" :key="d.docId" :value="d.docId">{{ d.title }}{{ d.searchable ? '' : '（索引尚未生效）' }}</option></select><p class="hub-field-note">来源列表显示前100份启用文档。请先在知识库维护并完成索引，再发布指南或常见问题。</p><label class="hub-checkbox"><input v-model="form.published" type="checkbox" />发布给学生（内容及来源须已核对）</label></template>
        <template v-if="dialog === 'ticket'"><label for="hub-ticket-title">咨询标题</label><input id="hub-ticket-title" v-model.trim="form.title" maxlength="140" required placeholder="用一句话描述你遇到的问题" /><label for="hub-ticket-category">咨询分类</label><select id="hub-ticket-category" v-model="form.category" required><option v-for="(name, code) in categories" :key="code" :value="code">{{ name }}</option></select><label for="hub-ticket-content">具体问题</label><textarea id="hub-ticket-content" v-model="form.content" maxlength="5000" rows="7" required placeholder="说明办理情形、你的疑问，以及已经查阅过的信息。"></textarea><p class="hub-field-note">咨询会由管理员处理，回复显示在工单中。请勿填写密码、完整证件号码等敏感信息。</p></template>
        <template v-if="dialog === 'reply'"><h3>{{ editing.title }}</h3><p class="hub-field-note">{{ editing.studentName }} · {{ editing.studentNo }} · {{ date(editing.createdAt) }}</p><div class="hub-detail-text">{{ editing.content }}</div><label for="hub-ticket-status">处理状态</label><select id="hub-ticket-status" v-model="form.status" required><option v-for="(name, code) in ticketStates" :key="code" :value="code">{{ name }}</option></select><label for="hub-ticket-reply">处理回复{{ form.status === 'RESOLVED' ? '（解决工单时必填）' : '' }}</label><textarea id="hub-ticket-reply" v-model="form.reply" maxlength="5000" rows="6" :required="form.status === 'RESOLVED'" placeholder="说明处理结果或下一步建议，必要时给出规章出处。"></textarea></template>
        <template v-if="dialog === 'user'"><template v-if="!editing"><label for="hub-username">登录账号</label><input id="hub-username" v-model.trim="form.username" maxlength="64" autocomplete="off" required /><label for="hub-student-no">学生学号</label><input id="hub-student-no" v-model.trim="form.studentNo" maxlength="32" required /></template><p v-else class="hub-field-note">账号：{{ editing.username }} · {{ editing.roleType === 'ADMIN' ? '管理员' : '学生' }}</p><label for="hub-real-name">真实姓名</label><input id="hub-real-name" v-model.trim="form.realName" maxlength="80" required /><template v-if="!editing"><label for="hub-new-password">初始密码</label><input id="hub-new-password" v-model="form.password" type="password" maxlength="72" autocomplete="new-password" required /><p class="hub-field-note">建议至少10位英文字母、数字与符号。此操作仅创建学生账号。</p></template><label v-else class="hub-checkbox"><input v-model="form.enabled" type="checkbox" :disabled="isSelf(editing)" />启用账号<small v-if="isSelf(editing)">（当前账号不能禁用）</small></label></template>
        <template v-if="dialog === 'password'"><p class="hub-field-note">为 {{ editing.realName }}（{{ editing.username }}）设置新密码。系统不会回显原密码。</p><label for="hub-reset-password">新密码</label><input id="hub-reset-password" v-model="form.password" type="password" maxlength="72" autocomplete="new-password" required /><label for="hub-confirm-password">再次输入新密码</label><input id="hub-confirm-password" v-model="form.confirmPassword" type="password" maxlength="72" autocomplete="new-password" required /><p class="hub-field-note">建议使用10—32位英文字母、数字与符号。</p></template>
        <template v-if="dialog === 'rename'"><label for="hub-session-title">会话标题</label><input id="hub-session-title" v-model.trim="form.title" maxlength="100" required /><p class="hub-field-note">重命名只更改标题，不影响原问答消息及引用。</p></template>
        <template v-if="dialog === 'note'"><h3>{{ editing.title }}</h3><label for="hub-note">我的阅读笔记</label><textarea id="hub-note" v-model="form.note" maxlength="500" rows="7" placeholder="记录要点、办理提醒或你的理解。"></textarea><p class="hub-field-note">笔记仅对你自己可见。</p></template>
        <div class="modal-footer"><button type="button" class="secondary" :disabled="busy" @click="closeDialog">取消</button><button type="submit" class="primary" :disabled="busy || (sourceLoading && ['guide', 'faq'].includes(dialog))"><span v-if="busy" class="spinner"></span>{{ busy ? '正在保存…' : dialog === 'ticket' ? '提交咨询' : dialog === 'password' ? '确认重置' : '保存' }}</button></div>
      </form>
    </section></div>
  </div>
</template>
