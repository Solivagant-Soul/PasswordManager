@phase-1  # 分类与标签管理已于 v0.8 拉回一期（设计稿评审，见需求 §13.0 C6）
Feature: 分类、标签与搜索
  作为用户
  我希望按分类、标签和关键字管理并查找条目
  以便在海量条目中快速定位

  Scenario: 关键字搜索命中
    Given 用户已解锁且库中存在匹配条目
    When 用户输入关键字搜索
    Then 系统返回命中标题、用户名或网址的条目

  Scenario: 搜索无结果
    Given 用户已解锁且库中无匹配条目
    When 用户输入关键字搜索
    Then 系统展示「无结果」空态
    But 不报错

  Scenario: 按分类过滤条目
    Given 用户已解锁且条目已设定分类
    When 用户按指定分类过滤
    Then 系统仅展示该分类下的条目

  Scenario: 分类名非法
    Given 用户已解锁并处于创建分类
    When 用户输入空白或超长的分类名
    Then 系统阻止创建
    And 给出提示

  Scenario: 标签名非法
    Given 用户已解锁并处于添加标签
    When 用户输入空白或超长的标签名
    Then 系统阻止添加
    And 给出提示

  Scenario: 删除分类后条目回退未分类
    Given 用户已解锁且某分类下有条目
    When 用户删除该分类
    Then 条目回退为「未分类」
    But 不被删除

  Scenario: 单条目标签达到上限
    Given 用户已解锁且某条目已有 20 个标签
    When 用户尝试为该条目再添加标签
    Then 系统提示标签已达上限
    And 阻止添加