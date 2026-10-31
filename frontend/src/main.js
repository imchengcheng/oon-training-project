import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Login from './pages/Login.vue'
import Student from './pages/Student.vue'
import Admin from './pages/Admin.vue'
import CampusHub from './pages/CampusHub.vue'
import { auth, clearAuth } from './state'
import './style.css'

const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: Login },
  { path: '/student', component: Student, meta: { role: 'STUDENT' } },
  { path: '/student/campus/:module?', component: CampusHub, meta: { role: 'STUDENT' } },
  { path: '/admin/campus/:module?', component: CampusHub, meta: { role: 'ADMIN' } },
  { path: '/admin/:tab?', component: Admin, meta: { role: 'ADMIN' } },
  { path: '/:pathMatch(.*)*', redirect: () => auth.user?.roleType === 'ADMIN' ? '/admin' : '/student' }
] })
router.beforeEach(to => {
  if (auth.token && auth.expiresAt <= Date.now()) clearAuth()
  if (to.meta.role && !auth.token) return '/login'
  if (to.meta.role && auth.user?.roleType !== to.meta.role) return auth.user?.roleType === 'ADMIN' ? '/admin' : '/student'
  if (to.path === '/login' && auth.token) return auth.user?.roleType === 'ADMIN' ? '/admin' : '/student'
})
window.addEventListener('auth-expired', () => router.replace('/login'))
createApp(App).use(router).mount('#app')
