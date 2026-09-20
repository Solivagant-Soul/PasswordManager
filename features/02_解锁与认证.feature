Feature: 解锁与认证
  作为用户
  我希望用主密码或生物识别解锁
  以便安全访问我的密码

  Scenario: 主密码正确解锁
    Given 密码库已初始化且处于锁定态
    When 用户输入正确的主密码
    Then 系统解锁
    And 展示密码条目列表

  Scenario: 主密码错误
    Given 密码库已初始化且处于锁定态
    When 用户输入错误的主密码
    Then 系统提示「主密码错误」
    But 保持锁定态

  Scenario: 连续输错五次触发限流
    Given 密码库已初始化且处于锁定态
    When 用户连续输错主密码 5 次
    Then 系统锁定 1 分钟
    And 提示剩余等待时间

  Scenario: 生物识别解锁成功
    Given 密码库已初始化，用户已启用生物识别，且处于锁定态
    When 用户通过生物识别
    Then 系统解锁
    And 进入密码列表

  Scenario: 生物识别不可用降级为主密码
    Given 密码库已初始化，用户已启用生物识别，且处于锁定态
    When 生物识别不可用或连续失败
    Then 系统降级为「请输入主密码」的解锁方式

  Scenario: 系统撤销生物识别授权后仅保留主密码
    Given 用户已在系统层撤销生物识别授权
    When 用户进入 App
    Then 系统仅提供主密码解锁
    And 给出提示

  Scenario: 锁定后立即再次进入仍需解锁
    Given 用户已解锁并主动锁定 App
    When 用户立即再次进入 App
    Then 系统仍处于锁定态
    And 要求重新认证