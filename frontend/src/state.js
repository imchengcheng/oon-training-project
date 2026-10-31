import { reactive } from 'vue'
let stored = {}
try { stored = JSON.parse(sessionStorage.getItem('campus-auth') || '{}') } catch { sessionStorage.removeItem('campus-auth') }
export const auth = reactive({ token: stored.token || '', user: stored.user || null, expiresAt: stored.expiresAt || 0 })
export function saveAuth(data) {
  Object.assign(auth, { token: data.token, user: data.user, expiresAt: Date.now() + data.expiresIn * 1000 })
  sessionStorage.setItem('campus-auth', JSON.stringify(auth))
}
export function clearAuth() { Object.assign(auth, { token: '', user: null, expiresAt: 0 }); sessionStorage.removeItem('campus-auth') }
export const categories = { STUDENT_STATUS: '学籍管理', DISCIPLINE: '奖惩办法', ACADEMIC_AFFAIRS: '教务流程', DORMITORY: '宿舍管理' }
export const statuses = { PENDING: '等待处理', PROCESSING: '处理中', COMPLETED: '处理完成', FAILED: '处理失败', DELETING: '正在删除', DELETED: '已删除' }
export function date(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
