<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api, query } from '../api'
import { auth, clearAuth, categories, date } from '../state'
import Icon from '../components/Icon.vue'
import BookmarkButton from '../components/BookmarkButton.vue'
import CampusModuleNav from '../components/CampusModuleNav.vue'
const router = useRouter(), route = useRoute()
const sessions = ref([]), totalSessions = ref(0), sessionPage = ref(1), sessionStatus = ref('')
const selected = ref(null), messages = ref([]), messagePage = ref(1), draft = ref(''), busy = ref(false), error = ref(''), source = ref(null), sourceBusy = ref(false), showSide = ref(false), chatArea = ref(null)
const feedbackTarget = ref(null), feedbackRemark = ref(''), feedbackRating = ref('LIKE'), feedbackBusy = ref(false)
const uncertain = ref(null)
const suggestions = ['办理休学需要什么手续？', '宿舍管理有哪些规定？', '考试违纪如何处理？', '奖学金申请有什么要求？']
const hasProcessing = computed(() => messages.value.some(m => m.processingStatus === 'PROCESSING'))
const canAsk = computed(() => !busy.value && !hasProcessing.value && selected.value?.status !== 'CLOSED')
function logout() { clearAuth(); router.push('/login') }
async function listSessions(reset = true) {
  if (reset) sessionPage.value = 1
  const data = await api(`/qa/sessions?${query({ page: sessionPage.value, size: 20, status: sessionStatus.value })}`)
  sessions.value = reset ? data.records : [...sessions.value, ...data.records]; totalSessions.value = data.total
}
async function moreSessions() { sessionPage.value++; try { await listSessions(false) } catch (e) { error.value = e.message; sessionPage.value-- } }
async function scrollBottom() { await nextTick(); chatArea.value?.scrollTo({ top: chatArea.value.scrollHeight, behavior: 'smooth' }) }
async function loadSession(id, latest = true) {
  const first = await api(`/qa/sessions/${id}?page=1&size=20`)
  let data = first
  if (latest && first.messages.total > 20) data = await api(`/qa/sessions/${id}?page=${Math.ceil(first.messages.total / 20)}&size=20`)
  if (selected.value?.sessionId !== id) return
  selected.value = data.session; messages.value = data.messages.records; messagePage.value = data.messages.page
}
async function select(s) { if (busy.value) return; selected.value = s; messages.value = []; error.value = ''; showSide.value = false; try { await loadSession(s.sessionId); await scrollBottom() } catch (e) { error.value = e.message } }
async function older() {
  if (messagePage.value <= 1) return
  try { const data = await api(`/qa/sessions/${selected.value.sessionId}?page=${messagePage.value - 1}&size=20`); messages.value = [...data.messages.records, ...messages.value]; messagePage.value-- } catch (e) { error.value = e.message }
}
function newChat() { if (busy.value) return; selected.value = null; messages.value = []; draft.value = ''; error.value = ''; uncertain.value = null; showSide.value = false }
async function ask(content = draft.value, requestId = null) {
  if (!canAsk.value || !content.trim()) return
  const clean = content.trim()
  if ([...clean].length > 1000) { error.value = '问题最多 1000 个字符'; return }
  busy.value = true; error.value = ''
  try {
    if (!selected.value) { selected.value = await api('/qa/sessions', { method: 'POST', body: {} }); await listSessions() }
    // Preserve the ID before making a request, so uncertain network results can be safely retried.
    const key = `campus-pending-${auth.user.userId}-${selected.value.sessionId}`
    let pending = null; try { pending = JSON.parse(sessionStorage.getItem(key) || 'null') } catch { /* invalid local state */ }
    const id = requestId || (pending?.content === clean ? pending.clientRequestId : crypto.randomUUID())
    sessionStorage.setItem(key, JSON.stringify({ clientRequestId: id, content: clean }))
    uncertain.value = { sessionId: selected.value.sessionId, clientRequestId: id, content: clean }
    draft.value = ''
    await api(`/qa/sessions/${selected.value.sessionId}/questions`, { method: 'POST', body: { content: clean, clientRequestId: id } })
    sessionStorage.removeItem(key); uncertain.value = null
  } catch (e) { error.value = `${e.message}${e.requestId ? `（追踪号：${e.requestId}）` : ''}` }
  finally {
    if (selected.value) try { await loadSession(selected.value.sessionId); await listSessions() } catch (e) { error.value ||= e.message }
    busy.value = false; await scrollBottom()
  }
}
async function closeSession() {
  if (!selected.value || !confirm('关闭后可查看历史，但无法继续提问。确认关闭此会话？')) return
  try { await api(`/qa/sessions/${selected.value.sessionId}/close`, { method: 'PUT' }); await loadSession(selected.value.sessionId); await listSessions() } catch (e) { error.value = e.message }
}
async function viewSource(message, item) {
  sourceBusy.value = true; source.value = null
  try { source.value = await api(`/qa/messages/${message.messageId}/sources/${item.sourceId}`) } catch (e) { error.value = e.message } finally { sourceBusy.value = false }
}
function openFeedback(message, rating) { feedbackTarget.value = message; feedbackRating.value = rating; feedbackRemark.value = message.feedback?.remark || '' }
function consult(message) {
  const question = messages.value.find(m => m.messageId === message.replyToMessageId)?.content || selected.value?.title || '规章咨询'
  router.push({ path: '/student/campus/tickets', query: { message: message.messageId, question: [...question].slice(0, 100).join('') } })
}
async function sendFeedback() {
  feedbackBusy.value = true
  try { const result = await api(`/qa/messages/${feedbackTarget.value.messageId}/feedback`, { method: 'PUT', body: { rating: feedbackRating.value, remark: feedbackRemark.value } }); feedbackTarget.value.feedback = result; feedbackTarget.value = null } catch (e) { error.value = e.message } finally { feedbackBusy.value = false }
}
let poll
onMounted(async () => {
  try {
    await listSessions()
    if (typeof route.query.question === 'string') draft.value = [...route.query.question].slice(0, 1000).join('')
    else if (typeof route.query.session === 'string' && /^[1-9][0-9]*$/.test(route.query.session)) {
      selected.value = { sessionId: route.query.session }
      try { await loadSession(route.query.session); await scrollBottom() }
      catch (e) { selected.value = null; throw e }
    }
  } catch (e) { error.value = e.message }
  poll = setInterval(async () => { if (!busy.value && hasProcessing.value && selected.value) try { await loadSession(selected.value.sessionId) } catch { /* error is recoverable through refresh */ } }, 4000)
})
onUnmounted(() => clearInterval(poll))
</script>
<template>
  <div class="workspace student-workspace">
    <button v-if="showSide" class="mobile-scrim" aria-label="关闭导航" @click="showSide = false"></button>
    <aside class="sidebar student-module-sidebar" :class="{ 'side-open': showSide }">
      <div class="brand"><span class="brand-mark"><Icon name="book" :size="23" /></span><div>校规知问<small>CAMPUS KNOWLEDGE</small></div></div>
      <div class="student-sidebar-body">
      <CampusModuleNav @navigate="showSide = false" />
      <button class="primary new-chat" @click="newChat" :disabled="busy"><Icon name="plus" :size="18" /> 开启新会话</button>
      <div class="side-label"><span>我的会话</span><select v-model="sessionStatus" aria-label="筛选会话" @change="listSessions().catch(e => error = e.message)"><option value="">全部</option><option value="ACTIVE">进行中</option><option value="CLOSED">已关闭</option></select></div>
      <nav class="session-list"><button v-for="s in sessions" :key="s.sessionId" :class="['session-link', { active: selected?.sessionId === s.sessionId }]" @click="select(s)"><Icon :name="s.status === 'CLOSED' ? 'lock' : 'chat'" :size="16" /><span>{{ s.title }}</span></button><p v-if="!sessions.length" class="side-empty">还没有会话，开始第一个问题吧</p><button v-if="sessions.length < totalSessions" class="text-button" @click="moreSessions">加载更多会话</button></nav>
      <div class="side-tip"><Icon name="shield" :size="19" /><strong>每一个答案，都有出处</strong><p>点击回答下方的来源卡片，<br>查看引用条款，核验原文。</p></div>
      </div>
      <div class="user-card"><div class="avatar">{{ auth.user?.realName?.slice(0, 1) }}</div><div><strong>{{ auth.user?.realName }}</strong><small>学生 · {{ auth.user?.studentNo }}</small></div><button class="icon-button" title="退出登录" aria-label="退出登录" @click="logout"><Icon name="logout" :size="18" /></button></div>
    </aside>
    <main class="main-panel">
      <header class="topbar"><div class="header-title"><button class="icon-button mobile-menu" aria-label="打开导航" @click="showSide = !showSide"><Icon name="menu" /></button><Icon name="chat" :size="18" /><span>{{ selected?.title || '智能问答' }}</span><span class="badge neutral" v-if="selected?.status === 'CLOSED'">已关闭</span></div><div class="header-actions"><span class="status-dot"></span><span class="muted small">基于校园规章知识库</span><button v-if="selected?.status === 'ACTIVE'" class="text-button" :disabled="busy || hasProcessing" @click="closeSession">关闭会话</button></div></header>
      <div v-if="error" class="error floating-error" role="alert">{{ error }}<button class="icon-button" aria-label="关闭提示" @click="error = ''"><Icon name="close" :size="16" /></button></div>
      <div v-if="uncertain && uncertain.sessionId === selected?.sessionId && !messages.some(m => m.clientRequestId === uncertain.clientRequestId)" class="notice floating-error">上次请求的接收结果尚未确认。<button class="text-button" :disabled="!canAsk" @click="ask(uncertain.content, uncertain.clientRequestId)">复用原请求标识重试</button></div>
      <section ref="chatArea" class="chat-scroll" aria-live="polite">
        <div v-if="!selected && !messages.length" class="welcome"><div class="welcome-emblem"><Icon name="spark" :size="34" /></div><div class="eyebrow">校园生活，有问有据</div><h1>你好，有什么可以帮你？</h1><p>关于学籍、教务、奖惩或宿舍的疑问，<br>试着问一问，答案就藏在学校的规章里。</p><div class="suggestion-grid"><button v-for="(s, i) in suggestions" :key="s" @click="draft = s"><span :class="['suggestion-icon', 'tone-' + i]"><Icon :name="['book', 'shield', 'file', 'spark'][i]" :size="21" /></span><span>{{ s }}</span><Icon name="chevron" :size="15" /></button></div><div class="welcome-foot"><span></span>也可以从下方输入你的具体问题<span></span></div></div>
        <div v-else class="message-list"><button v-if="messagePage > 1" class="text-button load-older" @click="older">加载更早的消息</button><p v-if="!messages.length && !busy" class="empty-state">从你的第一个问题开始</p>
          <article v-for="message in messages" :key="message.messageId" :class="['message', message.role.toLowerCase()]"><div class="message-avatar"><Icon v-if="message.role === 'ASSISTANT'" name="spark" :size="19" /><span v-else>{{ auth.user?.realName?.slice(0, 1) }}</span></div><div class="message-body"><div class="message-meta"><strong>{{ message.role === 'ASSISTANT' ? '校规知问' : '你' }}</strong><span>{{ date(message.createTime) }}</span><span v-if="message.answerStatus === 'NO_MATCH'" class="badge neutral">未找到条款</span><span v-if="message.answerStatus === 'INSUFFICIENT_EVIDENCE'" class="badge warning">证据不足</span></div><div class="message-text">{{ message.content }}</div>
            <div v-if="message.processingStatus === 'FAILED'" class="failure-inline"><span>处理失败 · {{ message.errorCode }}</span><button v-if="canAsk" class="text-button" @click="ask(message.content, message.clientRequestId)">重试此问题</button></div><div v-if="message.processingStatus === 'PROCESSING'" class="processing-inline"><span class="spinner"></span> 正在处理，可稍后刷新记录</div>
            <div v-if="message.sources?.length" class="source-section"><div class="source-heading"><Icon name="file" :size="14" /> 回答依据 <span>{{ message.sources.length }} 个来源</span></div><div class="source-cards"><button v-for="item in message.sources" :key="item.sourceId" class="source-card" @click="viewSource(message, item)"><span class="source-number">{{ item.rankNo }}</span><div><strong>{{ item.docTitle }}</strong><small>{{ categories[item.category] }}<template v-if="item.sectionLabel"> · {{ item.sectionLabel }}</template><template v-if="item.pageStart"> · 第 {{ item.pageStart }} 页</template></small><small v-if="item.sourceStatus !== 'ACTIVE'" class="warning-text">历史来源已更新或删除</small></div><Icon name="chevron" :size="15" /></button></div></div>
            <div v-if="message.role === 'ASSISTANT'" class="feedback-actions"><span>这个回答有帮助吗？</span><button :class="['icon-button', { chosen: message.feedback?.rating === 'LIKE' }]" aria-label="有帮助" @click="openFeedback(message, 'LIKE')"><Icon name="like" :size="15" /></button><button :class="['icon-button', { chosen: message.feedback?.rating === 'DISLIKE' }]" aria-label="没有帮助" @click="openFeedback(message, 'DISLIKE')"><Icon name="dislike" :size="15" /></button><BookmarkButton :target-id="message.messageId" /><button class="text-button" @click="consult(message)">转人工咨询</button></div>
          </div></article><div v-if="busy" class="thinking"><span class="spinner"></span> 正在检索规章并整理回答…</div></div>
      </section>
      <div class="composer-wrap"><form class="composer" @submit.prevent="ask()"><textarea v-model="draft" :disabled="!canAsk" :placeholder="selected?.status === 'CLOSED' ? '会话已关闭，开启新会话继续提问' : '描述你的问题，例如：申请休学需要准备哪些材料？'" aria-label="输入问题" rows="2" @keydown.enter.exact.prevent="ask()"></textarea><div class="composer-bottom"><span><Icon name="shield" :size="14" /> 依据原文作答，可查看条款来源</span><div><small :class="{ 'warning-text': [...draft].length > 1000 }">{{ [...draft].length }} / 1000</small><button class="send-button" aria-label="发送问题" :disabled="!canAsk || !draft.trim() || [...draft].length > 1000"><Icon name="arrow" :size="20" /></button></div></div></form><p class="disclaimer">回答仅作规章检索与解释辅助，正式认定以学校主管部门意见为准。<span>Enter 发送 · Shift + Enter 换行</span></p></div>
    </main>
    <div v-if="source || sourceBusy" class="modal-backdrop" @click.self="source = null; sourceBusy = false"><section class="modal source-modal" role="dialog" aria-modal="true" aria-label="引用原文"><div class="modal-header"><h2>引用原文</h2><button class="icon-button" aria-label="关闭原文" @click="source = null; sourceBusy = false"><Icon name="close" /></button></div><p v-if="sourceBusy">正在获取原文…</p><template v-if="source"><h3>{{ source.docTitle }}</h3><div class="source-detail-meta"><span class="pill">{{ categories[source.category] }}</span><span>{{ source.sectionLabel || '原文片段' }}</span><span v-if="source.pageStart">PDF 物理页码：{{ source.pageStart }}<template v-if="source.pageEnd !== source.pageStart">–{{ source.pageEnd }}</template></span></div><div v-if="source.sourceStatus !== 'ACTIVE'" class="notice">此来源已更新、删除或不可用。以下为回答时保存的历史快照，不代表当前有效规章。</div><div class="full-text">{{ source.fullText }}</div><p class="muted small">相似度 {{ source.similarityScore.toFixed(4) }} · 仅表示检索相关度</p></template></section></div>
    <div v-if="feedbackTarget" class="modal-backdrop" @click.self="feedbackTarget = null"><form class="modal compact-modal" role="dialog" aria-modal="true" aria-label="回答反馈" @submit.prevent="sendFeedback"><div class="modal-header"><h2>感谢你的反馈</h2><button type="button" class="icon-button" aria-label="关闭反馈" @click="feedbackTarget = null"><Icon name="close" /></button></div><p class="muted">你的建议可以帮助我们完善规章知识库。</p><textarea v-model="feedbackRemark" placeholder="补充意见（选填，最多 500 字）" rows="4" aria-label="反馈备注"></textarea><div class="modal-footer"><button type="button" class="secondary" @click="feedbackTarget = null">取消</button><button class="primary" :disabled="feedbackBusy || [...feedbackRemark].length > 500">{{ feedbackBusy ? '提交中…' : '提交反馈' }}</button></div></form></div>
  </div>
</template>
<style scoped>
.student-module-sidebar>.brand,.student-module-sidebar>.user-card{flex-shrink:0}.student-sidebar-body{display:flex;flex-direction:column;flex:1;min-height:0;overflow-y:auto;overscroll-behavior:contain;margin-top:4px;padding-right:2px}.student-sidebar-body .new-chat{margin:14px 0 18px;padding:11px;flex-shrink:0}.student-sidebar-body>.side-label{flex-shrink:0;margin-bottom:8px}.student-sidebar-body>.session-list{min-height:100px;flex:1 0 100px;overflow-y:auto}.student-sidebar-body>.side-tip{flex-shrink:0;margin:14px 0 18px}.student-module-sidebar>.user-card{background:#fff;margin-top:auto}.student-sidebar-body .session-link{padding-top:10px;padding-bottom:10px;font-size:11px}@media(max-height:950px){.student-sidebar-body>.side-tip{display:none}}@media(max-width:640px){.student-sidebar-body .new-chat{margin-top:12px;margin-bottom:16px}.student-module-sidebar>.user-card{padding-top:15px;padding-bottom:15px}.student-sidebar-body>.session-list{min-height:100px}.student-module-sidebar .side-empty{padding:12px;font-size:10px}}
</style>
<style scoped>
.feedback-actions{flex-wrap:wrap}
</style>
