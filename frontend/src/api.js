import { auth, clearAuth } from './state'
export class ApiError extends Error {
  constructor(message, code, data, requestId) { super(message); Object.assign(this, { code, data, requestId }) }
}
export async function api(path, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), options.timeout || 65000)
  const headers = { ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}), ...options.headers }
  let body = options.body
  if (body !== undefined && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body) }
  try {
    const response = await fetch(`/api${path}`, { ...options, body, headers, signal: controller.signal })
    const raw = await response.text()
    let result
    try { result = JSON.parse(raw) }
    catch {
      if (response.status === 403 && raw.trim() === 'Invalid CORS request') {
        throw new ApiError('请求来源未获允许，请检查页面地址与后端跨域配置', 'CORS_REJECTED', null, response.headers.get('X-Request-Id'))
      }
      const message = response.status >= 500
        ? `后端服务暂不可用（HTTP ${response.status}），请确认后端已启动后重试`
        : `服务响应异常（HTTP ${response.status}），请刷新页面后重试`
      throw new ApiError(message, 'INVALID_RESPONSE', null, response.headers.get('X-Request-Id'))
    }
    if (!result || typeof result !== 'object' || typeof result.code !== 'string') {
      throw new ApiError(`服务响应格式异常（HTTP ${response.status}），请确认访问的是本项目服务`, 'INVALID_RESPONSE')
    }
    if (!response.ok || result.code !== '0') {
      if (response.status === 401 && path !== '/auth/login') { clearAuth(); window.dispatchEvent(new Event('auth-expired')) }
      throw new ApiError(result.message || '请求失败', result.code, result.data, result.requestId)
    }
    return result.data
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(error.name === 'AbortError' ? '等待超时，请刷新记录确认处理结果后重试' : '无法连接服务，请确认前后端均已启动后重试', 'NETWORK')
  } finally { clearTimeout(timer) }
}
export function query(values) { const q = new URLSearchParams(); Object.entries(values).forEach(([k, v]) => { if (v !== '' && v != null) q.set(k, v) }); return q.toString() }
