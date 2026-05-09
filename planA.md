# Plan A — 假人合成系统“数据库化”改造计划

目标：将硬编码在 `CraftingBehavior.java` 中的合成配方抽离到外部 JSON 文件，实现逻辑与数据的彻底解耦，方便后续快速扩展（如铁器、附魔、药水等）。

---

## 阶段 1：数据库结构设计 (Architecture)

### 1.1 定义 JSON 格式 (`config/maohi_recipes.json`)
设计一个既能表达 2x2（背包）又能表达 3x3（工作台）的通用格式。
```json
{
  "minecraft:stick": {
    "is_2x2": true,
    "ingredients": [
      {"item": "minecraft:oak_planks", "slot": 1},
      {"item": "minecraft:oak_planks", "slot": 3}
    ]
  },
  "minecraft:stone_pickaxe": {
    "is_2x2": false,
    "ingredients": [
      {"item": "minecraft:cobblestone", "slot": 1},
      {"item": "minecraft:cobblestone", "slot": 2},
      {"item": "minecraft:cobblestone", "slot": 3},
      {"item": "minecraft:stick", "slot": 5},
      {"item": "minecraft:stick", "slot": 8}
    ]
  }
}
```

### 1.2 创建中间层 `RecipeBank.java`
*   **职责**：负责在服务器启动时读取 JSON，并将其解析为内存中的 `Map<Item, RecipeData>`。
*   **核心方法**：`RecipeData getRecipe(Item target)`。

---

## 阶段 2：存量数据迁移 (Migration)

### 2.1 搬家工程
*   将 `CraftingBehavior.recipeFor()` 中现有的 10 余种配方（木板、木棍、工作台、木/石工具、熔炉、信标）全部转写到 JSON 文件中。
*   **注意**：保留“模糊匹配”逻辑（例如 `oak_log` 代表所有日志），这需要在加载 JSON 时进行特殊处理。

---

## 阶段 3：代码逻辑重构 (Integration)

### 3.1 简化 `CraftingBehavior.java`
*   **清理**：删除冗长的 `recipeFor` 方法和硬编码的列表。
*   **重构 `executeCraft`**：
    *   调用 `RecipeBank.getRecipe(target)`。
    *   根据返回的 `RecipeData.is_2x2` 自动决定走 `executeInInventoryCraft` 还是 `executeCraft`。
    *   不再需要手动判断是否为 `Items.STICK` 或 `Items.OAK_PLANKS`。

---

## 阶段 4：功能增强 (Future Proofing)

### 4.1 动态重载功能
*   增加一个指令 `/maohi reload recipes`。
*   **效果**：不用重启服务器，修改 JSON 后立刻让全服假人学会新配方。

### 4.2 多配方支持
*   支持同一个目标物品有多种合成方式（比如用不同木头合成木棍）。

---

## 验证流程
1.  **静态验证**：启动服务器，检查日志中是否有 `[RecipeBank] Loaded 15 recipes`。
2.  **动态验证**：
    *   清空一个假人的背包。
    *   给他原木。
    *   观察他是否能根据 JSON 里的配方，一步步合成出石镐并触发成就。
3.  **扩展测试**：在 JSON 里加一个原本没有的配方（比如铁剑），手动给假人铁锭，看他是否能学会。
