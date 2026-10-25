/// <reference types="vite/client" />

declare module 'virtual:prep-content' {
  import type { Content } from './types'
  const content: Content
  export default content
}

interface ImportMetaEnv {
  readonly VITE_STATIC?: string
  readonly VITE_INTERVIEW_DATE?: string
}
