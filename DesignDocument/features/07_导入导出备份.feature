  @phase-2  # 加密备份/导入移二期（2026-09-24）；CSV 导入已彻底删除
Feature: 导入、导出与备份
  作为用户
  我希望导出加密备份并恢复
  以便在换机或丢失时找回数据

  Scenario: 加密导出备份
    Given 用户已解锁且库中有条目
    When 用户导出并使用主密码或独立口令
    Then 系统生成口令保护的加密备份文件

  Scenario: 导入恢复成功
    Given 用户已解锁且拥有合法加密备份
    When 用户导入并输入正确口令
    Then 系统恢复备份中的条目

  Scenario: 导入口令错误
    Given 用户尝试导入加密备份
    When 用户输入错误口令
    Then 系统提示失败
    But 不将任何数据写入密码库

  Scenario: 备份文件损坏
    Given 用户尝试导入加密备份
    When 备份文件已损坏
    Then 系统提示失败
    But 现有数据不受影响




  Scenario: 导出过程中断不产生损坏文件
    Given 用户已解锁且正在导出
    When 导出因存储不足或中断而失败
    Then 系统不产生看似成功实则损坏的备份文件

  Scenario: 空库导出
    Given 用户已解锁且密码库为空
    When 用户执行导出
    Then 系统生成空备份文件或明确提示