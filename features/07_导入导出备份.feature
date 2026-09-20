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

  Scenario: CSV 导入成功
    Given 用户已解锁且提供符合模板的 CSV
    When 用户导入该 CSV
    Then 系统将条目写入本地密码库

  Scenario: CSV 缺列报错
    Given 用户尝试导入 CSV
    When 文件缺列或字段格式错误
    Then 系统报错并指出问题
    And 不写入脏数据

  Scenario: CSV 重复条目跳过
    Given 用户已解锁且 CSV 中存在与现有条目重复的记录
    When 用户导入该 CSV
    Then 系统跳过重复条目
    And 汇总跳过数量

  Scenario: 导出过程中断不产生损坏文件
    Given 用户已解锁且正在导出
    When 导出因存储不足或中断而失败
    Then 系统不产生看似成功实则损坏的备份文件

  Scenario: 空库导出
    Given 用户已解锁且密码库为空
    When 用户执行导出
    Then 系统生成空备份文件或明确提示