# Git 双人协作规范（共用 fix 分支）

## 分支结构

```
main    ← 稳定版本，不直接开发
fix     ← 你和朋友共用的开发分支
```

两个人都在 fix 分支上开发，main 用于存档稳定版本。

---

## 日常开发流程

### 第1步：开发前（必须做）

```bash
git checkout fix
git pull origin fix     # 拉最新代码，这步不能省
```

### 第2步：写代码

正常改文件、新增文件。

### 第3步：提交推送

```bash
git add .
git commit -m "改了什么"
git pull origin fix     # 再拉一次（防止对方刚推了）
git push origin fix
```

**两次 pull 的含义**：
- 第一次 pull：确保你从最新代码开始写
- 第二次 pull：确保你推的时候不会被拒绝

---

## 冲突处理

### 什么时候会冲突

你和朋友改了同一个文件的同一个位置，后推的人会遇到冲突。

### 冲突的表现

`git push` 被拒绝，提示：

```
Updates were rejected because the remote contains work that you do not have locally.
```

或者 `git pull` 时提示：

```
CONFLICT (content): Merge conflict in xxx.java
```

### 解决步骤

```bash
# 1. 看哪些文件冲突
git status

# 2. 打开冲突文件，找到冲突标记
```

文件里会看到：

```java
<<<<<<< HEAD
    // 对方的代码
    String result = methodA();
=======
    // 你的代码
    String result = methodB();
>>>>>>> fix
```

```bash
# 3. 删掉 <<<<<<< ======= >>>>>>> 三行标记，保留正确的代码

# 4. 标记已解决
git add 冲突文件.java

# 5. 提交
git commit -m "解决冲突"

# 6. 推送
git push origin fix
```

### 快速策略

| 情况 | 命令 |
|------|------|
| 保留对方的版本 | `git checkout --ours 冲突文件.java` |
| 保留自己的版本 | `git checkout --theirs 冲突文件.java` |
| 手动合并（推荐） | 打开文件，两边都看，手动选 |

---

## 减少冲突的技巧

1. **改代码前先 pull** — 每次都要，不能省
2. **小步提交** — 改一点就推，别攒一大堆再推
3. **改公共文件先说一声** — `pom.xml`、`application.yml`、`application.properties` 这些文件两个人都可能改，改之前跟对方说一声
4. **新增文件不冲突** — 你新建一个类，朋友新建一个类，不会冲突

---

## 合并到 main

开发到一个稳定版本后，把 fix 合并到 main 存档：

```bash
git checkout main
git pull origin main
git merge fix
git push origin main
```

---

## 常见问题

| 问题 | 解决 |
|------|------|
| push 被拒绝 | `git pull origin fix`，再 `git push origin fix` |
| pull 时冲突 | 解决冲突 → `git add` → `git commit` |
| 想撤销本次修改（没提交前） | `git checkout -- 文件名` |
| 想撤销最近一次提交 | `git reset --soft HEAD~1` |
| 想看改了什么 | `git status` 或 `git diff` |
| 撤销合并 | `git merge --abort` |

---

## 一句话总结

**开发前 pull，改完就 commit，提交前再 pull，有冲突当场解决。**
