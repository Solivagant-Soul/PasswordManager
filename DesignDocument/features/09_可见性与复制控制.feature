Feature: 可见性与复制控制
  作为用户
  我希望密码默认掩码显示、复制后自动清理剪贴板
  以便降低泄露风险

  Scenario: 密码默认掩码显示
    Given 用户已解锁并查看条目
    When 用户进入条目详情
    Then 密码以掩码形式显示

  Scenario: 点按眼睛临时明文
    Given 用户已解锁并查看掩码显示的条目
    When 用户点按「眼睛」
    Then 密码临时明文显示
    And 松开后恢复掩码

  Scenario: 复制后 30 秒自动清理剪贴板
    Given 用户已解锁并复制了某密码
    When 超过 30 秒
    Then 系统清除剪贴板中的敏感内容

  Scenario: 离开页面后明文关闭
    Given 用户已解锁且密码处于明文显示
    When 用户离开条目页或 App 锁定
    Then 明文显示立即关闭
    But 不残留明文

  Scenario: 系统不支持清空剪贴板时提示
    Given 用户已解锁并复制了某密码
    When 系统不支持主动清空剪贴板
    Then 系统移除 App 内明文痕迹
    And 给出提示