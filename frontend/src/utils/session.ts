// 会话字段只承载前端认证凭据与展示身份，不替代后端权限验证。
export interface Session { token: string; user: string }

// 每次读取最新会话，避免退出或切换账号后沿用旧身份。
export function readSession(): Session {
  return {
    // 读取认证令牌和用户名，未登录时使用空字符串。
    token: localStorage.getItem('social_token') || '',
    user: localStorage.getItem('social_user') || ''
  }
}

// 登录成功后持久化令牌和用户名，清理旧版本的角色缓存。
export function saveSession(user: { token: string; username: string }) {
  // 身份展示与认证令牌分别保存。
  localStorage.setItem('social_token', user.token)
  localStorage.setItem('social_user', user.username)
  localStorage.removeItem('social_role')
}

// 登录失效、退出和改密共用清理规则；请求缓存仍由调用方清理。
export function clearSession() {
  // 只删除本系统会话键，不清空主题等其他本地配置。
  localStorage.removeItem('social_token')
  localStorage.removeItem('social_user')
  localStorage.removeItem('social_role')
}
