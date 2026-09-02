export interface User {
  id: string
  username: string
  displayName: string
  email?: string
  phone?: string
  role: string
  status: string
  organizationId?: string
  organizationName?: string
  avatarUrl?: string
  gender?: string
  birthDate?: string
  education?: string
  jobTitle?: string
  bio?: string
}

export interface Conversation {
  id: string
  title: string
  status: string
  createdAt: string
  updatedAt: string
  lastMessageAt?: string
}

export interface Message {
  id?: string
  role: 'USER' | 'ASSISTANT'
  content: string
  status: string
  errorMessage?: string
  createdAt?: string
}

