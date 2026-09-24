Feature: 关于与安全提示
  作为用户
  我希望查看版本信息与安全声明
  以便了解数据存储方式和风险

  Scenario: 查看版本与安全声明
    Given 用户已解锁并进入关于页面
    When 用户查看版本信息与数据存储声明
    Then 系统展示版本号
    And 展示「数据仅存本机」的说明

  @phase-2  # root 检测属 T29 硬化，移二期
  Scenario: 越狱或 root 设备风险提示
    Given 用户在越狱或 root 设备上运行 App
    When 系统检测到越狱或 root
    Then 系统给出风险提示
    But 不阻止使用