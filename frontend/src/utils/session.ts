export interface Session { token: string; user: string; role: string }

// 每次读取最新会话，避免退出或切换账号后沿用旧身份。
export function readSession(): Session {
  return {
    token: localStorage.getItem('social_token') || '',
    user: localStorage.getItem('social_user') || '',
    role: localStorage.getItem('social_role') || ''
  }
}

export function saveSession(user: { token: string; username: string; role: string }) {
  localStorage.setItem('social_token', user.token)
  localStorage.setItem('social_user', user.username)
  localStorage.setItem('social_role', user.role)
}

// 登录失效、退出和改密共用清理规则；请求缓存仍由调用方清理。
export function clearSession() {
  localStorage.removeItem('social_token')
  localStorage.removeItem('social_user')
  localStorage.removeItem('social_role')
}
