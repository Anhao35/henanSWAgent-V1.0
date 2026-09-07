import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import ForgotPasswordView from './views/ForgotPasswordView.vue'
import ResetPasswordView from './views/ResetPasswordView.vue'
import WorkspaceView from './views/WorkspaceView.vue'
import ProfileView from './views/ProfileView.vue'
import AdminUsersView from './views/AdminUsersView.vue'
import ResearchView from './views/ResearchView.vue'
import MitreMapperView from './views/MitreMapperView.vue'
import ReportCenterView from './views/ReportCenterView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/workspace' },
    { path: '/login', component: LoginView, meta: { guest: true } },
    { path: '/register', component: RegisterView, meta: { guest: true } },
    { path: '/forgot-password', component: ForgotPasswordView, meta: { guest: true } },
    { path: '/reset-password', component: ResetPasswordView, meta: { guest: true } },
    { path: '/workspace', component: WorkspaceView, meta: { requiresAuth: true } },
    { path: '/workspace/chat/:id', component: WorkspaceView, meta: { requiresAuth: true } },
    { path: '/research', component: ResearchView, meta: { requiresAuth: true } },
    { path: '/mitre-mapper', component: MitreMapperView, meta: { requiresAuth: true } },
    { path: '/reports', component: ReportCenterView, meta: { requiresAuth: true } },
    { path: '/settings/profile', component: ProfileView, meta: { requiresAuth: true } },
    { path: '/admin/users', component: AdminUsersView, meta: { requiresAuth: true, admin: true } },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.load()
  if (to.meta.requiresAuth && !auth.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !['SUPER_ADMIN', 'ORG_ADMIN'].includes(auth.user?.role || '')) return '/workspace'
  if (to.meta.guest && auth.user) return '/workspace'
})

export default router
