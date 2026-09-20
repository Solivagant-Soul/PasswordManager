Feature: 动态验证码（TOTP）
  作为用户
  我希望能为账号保存并生成 TOTP 动态验证码
  以便完成双重验证登录

  Scenario: 展示动态验证码
    Given 用户已解锁且条目已录入合法 TOTP 密钥
    When 用户进入该条目详情
    Then 系统显示 6 位动态码
    And 显示 30 秒倒计时

  Scenario: 复制动态验证码
    Given 用户已解锁且条目已录入合法 TOTP 密钥
    When 用户复制动态码
    Then 系统将动态码写入剪贴板
    And 按 30 秒规则清理

  Scenario: 密钥格式非法
    Given 用户已解锁并录入 TOTP 密钥
    When 密钥格式非法
    Then 系统提示格式错误
    But 不保存该密钥

  Scenario: 本机时间偏差提示
    Given 用户已解锁且条目已录入 TOTP 密钥
    When 本机系统时间偏差过大
    Then 系统提示检查设备时间

  Scenario: 倒计时滚动刷新
    Given 用户已解锁且正在查看动态码
    When 30 秒倒计时结束
    Then 系统自动滚动为新的动态码
    But 不出现跳变