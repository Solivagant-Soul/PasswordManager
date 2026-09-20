Feature: 密码条目增删改查
  作为用户
  我希望新增、查看、编辑、删除密码条目
  以便持续维护我的凭据信息

  Scenario: 新增条目成功
    Given 用户已解锁
    When 用户填写标题与密码并保存
    Then 系统保存条目
    And 列表中出现该条目且可被搜索到

  Scenario: 标题为空阻止保存
    Given 用户已解锁并处于新增条目页面
    When 用户未填写标题
    Then 系统阻止保存
    And 高亮标题为必填

  Scenario: 密码为空阻止保存
    Given 用户已解锁并处于新增条目页面
    When 用户未填写密码
    Then 系统阻止保存
    And 高亮密码为必填

  Scenario: 网址非法阻止保存
    Given 用户已解锁并处于新增条目页面
    When 用户填写不符合网址规范的网址
    Then 系统提示网址不合法
    And 阻止保存

  Scenario: 编辑保存成功
    Given 用户已解锁且存在某条目
    When 用户修改条目字段并保存
    Then 系统更新该条目
    And 保存新值

  Scenario: 删除条目触发二次确认
    Given 用户已解锁并选中某条目
    When 用户点击删除
    Then 系统弹出二次确认

  Scenario: 取消删除条目
    Given 用户已解锁并处于删除确认弹窗中
    When 用户取消删除
    Then 系统保留该条目

  Scenario: 确认删除条目
    Given 用户已解锁并处于删除确认弹窗中
    When 用户确认删除
    Then 系统永久删除该条目

  Scenario: 保存失败不产生残缺条目
    Given 用户已解锁且正在保存条目
    When 存储读写失败
    Then 系统提示保存失败
    And 不产生残缺条目

  Scenario: 编辑中锁定导致修改丢失
    Given 用户已解锁并正在编辑条目
    When App 切换到后台并达到锁定条件
    Then 系统重新解锁后提示未保存的修改已丢失

  Scenario: 条目数量达到上限
    Given 用户已解锁且密码库已有 5000 条条目
    When 用户尝试新增条目
    Then 系统提示已达上限
    And 禁止新增

  Scenario: 字段为最大长度可保存
    Given 用户已解锁并处于新增条目页面
    When 用户填写各字段为最大长度
    Then 系统接受并保存该条目