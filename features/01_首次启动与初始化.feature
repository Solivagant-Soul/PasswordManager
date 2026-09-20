Feature: 首次启动与初始化
  作为用户
  我希望在首次打开 App 时设置主密码与密保问题
  以便建立本地密码库并具备找回能力

  Scenario: 首次启动设置主密码成功
    Given 用户首次打开尚未初始化的 App
    When 用户输入主密码并再次输入一致的确认密码，且满足强度要求
    Then 系统创建密码库
    And 进入主界面

  Scenario: 两次主密码不一致
    Given 用户处于主密码设置流程
    When 用户两次输入的主密码不一致
    Then 系统提示「两次输入不一致」
    But 不进入主界面

  Scenario: 主密码长度不足
    Given 用户处于主密码设置流程
    When 用户输入的主密码少于 8 位
    Then 系统提示长度不足
    And 阻止提交

  Scenario: 主密码仅含一类字符
    Given 用户处于主密码设置流程
    When 用户输入的主密码仅含一类字符
    Then 系统提示需至少两类字符
    And 阻止提交

  Scenario: 主密码为纯空白字符串
    Given 用户处于主密码设置流程
    When 用户输入仅由空白字符组成的主密码
    Then 系统拒绝该主密码
    And 给出提示

  Scenario: 用户拒绝设置主密码
    Given 用户首次打开尚未初始化的 App
    When 用户跳过或拒绝设置主密码
    Then 系统提示未设置主密码前无法使用
    And 返回设置流程

  Scenario: 初始化中途中断不产生脏数据
    Given 用户正在设置主密码
    When 设置过程中 App 被杀掉
    Then 下次打开时系统仍处于未初始化状态
    And 要求用户重新设置

  Scenario: 主密码长度为最小边界值
    Given 用户处于主密码设置流程
    When 用户输入长度为 8 且满足强度要求的主密码
    Then 系统接受该主密码

  Scenario: 主密码长度为最大边界值
    Given 用户处于主密码设置流程
    When 用户输入长度为 64 且满足强度要求的主密码
    Then 系统接受该主密码

  Scenario: 主密码含不可见控制字符
    Given 用户处于主密码设置流程
    When 用户输入含不可见控制字符的主密码
    Then 系统拒绝该主密码
    And 给出提示

  Scenario: 设置密保问题成功
    Given 用户已完成主密码设置
    When 用户录入 3 道密保问题及答案并提交
    Then 系统保存密保信息
    And 完成初始化

  Scenario: 密保问题未答满 3 道
    Given 用户已完成主密码设置并处于密保问题设置流程
    When 用户仅录入不足 3 道密保问题
    Then 系统提示需填满 3 道
    And 阻止完成