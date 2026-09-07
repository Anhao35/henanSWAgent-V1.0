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
  runId?: string
  taskMode?: string
  id?: string
  role: 'USER' | 'ASSISTANT'
  content: string
  status: string
  errorMessage?: string
  createdAt?: string
  attachments?: Attachment[]
}

export interface Attachment {
  id: string
  name: string
  contentType: string
  sizeBytes: number
  downloadUrl: string
}

export interface ResearchItem {
  id?: string
  source: string
  sourceId: string
  itemType: 'PAPER' | 'BOOK'
  title: string
  authors: string[]
  publicationYear?: number
  venue?: string
  abstractText?: string
  doi?: string
  sourceUrl?: string
  openAccessUrl?: string
  citationCount?: number
  score?: number
  createdAt?: string
}

export interface ResearchSearchResponse {
  searchId: string
  query: string
  total: number
  durationMs: number
  cached: boolean
  warnings: string[]
  items: ResearchItem[]
}

export interface ResearchHistory {
  id: string
  query: string
  sources?: string
  resultCount: number
  durationMs: number
  status: string
  createdAt: string
}

export interface MitreAttackMapping {
  behaviorOrder?: number
  tacticId: string
  tacticName: string
  techniqueId: string
  techniqueName: string
  confidence: number
  evidence: string
  reasoning: string
  identifierFormatValid: boolean
  evidenceMatched: boolean
}

export interface MitreCweMapping {
  cweId: string
  cweName: string
  confidence: number
  evidence: string
  reasoning: string
  identifierFormatValid: boolean
  evidenceMatched: boolean
}

export interface MitreMappingResult {
  id: string
  type: 'ATTACK' | 'CWE'
  summary: string
  attackBehaviors: string[]
  attackMappings: MitreAttackMapping[]
  cweMappings: MitreCweMapping[]
  unmappedObservations: string[]
  model: string
  cached: boolean
  promptTokens?: number
  completionTokens?: number
  createdAt: string
}

export interface MitreMappingHistory {
  id: string
  type: 'ATTACK' | 'CWE'
  inputPreview: string
  summary: string
  mappingCount: number
  status: string
  model?: string
  createdAt: string
}
