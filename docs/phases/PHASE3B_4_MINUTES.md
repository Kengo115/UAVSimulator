# Phase 3b-4 実装議事録：Luaスクリプト原子操作

**実施日**: 2025-12-29
**担当者**: Claude (Sonnet 4.5)
**コミット**: fda0ed2 Phase3b-4: Luaスクリプトによる原子操作保証

---

## 目的

**Luaスクリプトによる完全な原子操作**

Phase 3b-3では楽観的ロック方式（デクリメント→負なら戻す）を使用していましたが、複数Workerが同時に同じリンクを処理すると一瞬だけ不整合が発生する可能性がありました。Phase 3b-4ではLuaスクリプトを使用して、容量操作を完全に原子的にします。

---

## 楽観的ロック vs Luaスクリプト

| 項目 | 楽観的ロック（Phase 3b-3） | Luaスクリプト（Phase 3b-4） |
|------|--------------------------|---------------------------|
| 原子性 | 個別操作のみ | **完全に原子的** |
| 競合ウィンドウ | あり（一瞬） | **なし** |
| 負の容量発生 | 可能性あり | **不可能** |
| Redis呼び出し回数 | 2回（失敗時） | **1回** |

---

## 実装した機能

### 1. 容量消費用Luaスクリプト

```lua
-- KEYS[1]: 容量キー (link:X:Y:capacity)
-- 戻り値: 1 = 成功, 0 = 失敗（容量不足）
local capacity = tonumber(redis.call('GET', KEYS[1])) or 0
if capacity >= 1 then
    redis.call('INCRBYFLOAT', KEYS[1], -1)
    return 1
else
    return 0
end
```

### 2. 容量回復用Luaスクリプト

```lua
-- KEYS[1]: 容量キー (link:X:Y:capacity)
-- 戻り値: 回復後の容量
local newCapacity = redis.call('INCRBYFLOAT', KEYS[1], 1)
return newCapacity
```

### 3. LinkCapacityManager.java 修正

**tryConsumeCapacity()（Luaスクリプト版）**:
```java
public boolean tryConsumeCapacity(int srcNode, int dstNode) {
    String key = "link:" + srcNode + ":" + dstNode + ":capacity";

    // Luaスクリプトで原子的にチェック→消費
    Long result = script.eval(
        RScript.Mode.READ_WRITE,
        CONSUME_CAPACITY_SCRIPT,
        RScript.ReturnType.INTEGER,
        Collections.singletonList(key)
    );

    return (result != null && result == 1);
}
```

**recoverCapacity()（Luaスクリプト版）**:
```java
public double recoverCapacity(int srcNode, int dstNode) {
    String key = "link:" + srcNode + ":" + dstNode + ":capacity";

    Object result = script.eval(
        RScript.Mode.READ_WRITE,
        RECOVER_CAPACITY_SCRIPT,
        RScript.ReturnType.VALUE,
        Collections.singletonList(key)
    );

    if (result instanceof Number) {
        return ((Number) result).doubleValue();
    }
    return 0.0;
}
```

---

## テスト結果

```
Phase 3b-4: link[0][1] 容量消費成功（Lua原子操作）
Phase 3b-4: link[0][1] 容量消費成功（Lua原子操作）
...
Phase 3b-4: link[0][1] 容量回復 → 97.0（Lua原子操作）
Phase 3b-4: link[0][1] 容量回復 → 100.0（Lua原子操作）
...
完了UAV: 5/5
リンク通過: 15/15
```

---

## 検証ポイント

| 項目 | 結果 |
|------|------|
| Luaスクリプト実行 | 正常動作 |
| 容量消費（原子的） | 15回全て成功 |
| 容量回復（原子的） | 15回全て成功 |
| 既存テスト互換性 | AsyncFlightTest通過 |
| 競合ウィンドウ | 完全に排除 |

---

## 次のステップ

Phase 3b-5: 途中リンク待機・再開へ進む
