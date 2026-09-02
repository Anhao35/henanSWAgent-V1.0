import { defineStore } from 'pinia'
import { api } from '../api'
import type { User } from '../types'

export const useAuthStore = defineStore('auth', {
  state: () => ({ user: null as User | null, initialized: false }),
  actions: {
    async load() {
      if (this.initialized) return
      try {
        const { data } = await api.get<User>('/auth/me')
        this.user = data
      } catch {
        this.user = null
      } finally {
        this.initialized = true
      }
    },
    async login(login: string, password: string) {
      const { data } = await api.post('/auth/login', { login, password })
      this.user = data.user
      this.initialized = true
    },
    async logout() {
      await api.post('/auth/logout')
      this.user = null
      this.initialized = true
    },
    setUser(user: User) {
      this.user = user
    },
  },
})

