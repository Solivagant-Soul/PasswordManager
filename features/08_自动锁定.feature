Feature: 自动锁定
  作为用户
  我希望 App 在后台或空闲时自动锁定
  以便防止他人偷看

  Scenario: 后台超时自动锁定
    Given 用户已解锁并切到后台
    When 超过 1 分钟回到前台
    Then 系统进入锁定态
    And 要求重新认证

  Scenario: 未达阈值不锁定
    Given 用户已解锁并短暂切到后台
    When 未超过 1 分钟即回到前台
    Then 系统保持解锁状态

  Scenario: 主动锁定立即生效
    Given 用户已解锁
    When 用户主动锁定 App
    Then 系统立即进入锁定态

  Scenario: 锁定状态写入失败按锁定处理
    Given 用户已解锁并即将锁定
    When 锁定状态写入失败
    Then 系统下次进入时按锁定态处理