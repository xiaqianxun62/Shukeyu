-- 浏览访问日志：记录访问者 IP、来源页面、浏览器标识等
CREATE TABLE visit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ip VARCHAR(64) NOT NULL DEFAULT '' COMMENT '访问者 IP（X-Forwarded-For 优先，取首个非 unknown）',
  user_id BIGINT NULL COMMENT '登录用户 ID，未登录为 NULL',
  method VARCHAR(8) NOT NULL DEFAULT 'GET' COMMENT 'HTTP 方法',
  path VARCHAR(255) NOT NULL DEFAULT '' COMMENT '请求路径（含查询串）',
  user_agent VARCHAR(500) NOT NULL DEFAULT '' COMMENT '浏览器 UA',
  referer VARCHAR(500) NOT NULL DEFAULT '' COMMENT '来源页 URL',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
  INDEX idx_create_time (create_time),
  INDEX idx_ip (ip),
  INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '浏览访问日志（IP 记录）';
