---
name: controller-no-mapper-injection
description: Controller 层禁止直接注入 Mapper，必须通过 Service 层封装
metadata:
  type: feedback
---

Controller 中不能直接引入 Mapper。需要查询数据时，在 Service 中添加对应方法，由 Service 引入 Mapper。

**Why:** 分层架构规范，Controller 只负责请求路由和参数处理，数据访问逻辑统一在 Service 层。

**How to apply:** 任何需要数据库操作的场景，先在 Service 中写方法（内部注入 Mapper），Controller 通过调用 Service 方法获取数据。