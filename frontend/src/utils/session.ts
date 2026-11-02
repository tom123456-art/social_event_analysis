// 会话字段只承载前端认证凭据与展示身份，不替代后端权限验证。
export interface Session { token: string; user: string; role: string }

// 每次读取最新会话，避免退出或切换账号后沿用旧身份。
export function readSession(): Session {
  return {
    // 分别读取认证令牌、用户名和角色；未登录时使用空字符串避免返回空值。
    token: localStorage.getItem('social_token') || '',
    user: localStorage.getItem('social_user') || '',
    role: localStorage.getItem('social_role') || ''
  }
}

// 登录成功后持久化令牌、用户名和角色，供后续请求与路由守卫读取。
export function saveSession(user: { token: string; username: string; role: string }) {
  // 分别写入三个本地存储键，保持读取与清理的口径一致。
  localStorage.setItem('social_token', user.token)
  localStorage.setItem('social_user', user.username)
  localStorage.setItem('social_role', user.role)
}

// 登录失效、退出和改密共用清理规则；请求缓存仍由调用方清理。
export function clearSession() {
  // 只删除本系统会话键，不清空主题等其他本地配置。
  localStorage.removeItem('social_token')
  localStorage.removeItem('social_user')
  localStorage.removeItem('social_role')
}
