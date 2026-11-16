# Rift 核心补丁（patches/）

官方 sing-box 源码由 `buildScript/lib/core/get_source.sh` 在构建时从上游
克隆（验 tag 与 commit，不改官方源码）。这里的 `.patch` 文件在克隆、
校验通过后打到 `../sing-box` 上，再编译。补丁是 Rift 的第二块核心补丁
（第一块是 libcore 内的 XHTTP graft）。

## reality-mlkem-keep-share.patch

- 改动：`common/tls/reality_client.go`（sing-box v1.15.0-alpha.13）
- 来源：Marco9442 的条件保留分支（commit b09c7e5），与上游 alpha.13
  的该文件除补丁处外完全一致；补丁后的文件与该分支逐字节相同。
- 原因：sing-box #4520。Xray-core ≥26.9.8 的 REALITY 服务端强制要求
  ClientHello 带 X25519MLKEM768 混合 share，否则报 reality verification
  failed；sing-box 上游把该 share 无条件去掉。条件保留只在指纹同时
  给出 group 与 share 时保留（Chrome 指纹即如此），group 与 share
  不成对时仍去掉（避免 HelloRetryRequest 与 RFC 8446 §4.2.8 问题），
  因此老 Xray 节点不受影响。
- 验证：2026-11-09，作者在 Xray 26.10.21 上验证通过；Rift 预览
  pre-0.2.10-20261109-1 由用户实测新老两节点（Vmiss 26.10.21 与新机）。

## 维护规则

- 每次升 SINGBOX_VERSION 都要确认本补丁还能打上、且上游是否已修 #4520。
- 上游修了（补丁打不上或已无必要）就删 `.patch` 文件与
  `get_source.sh` 里的打补丁段，不要留死代码。
- 补丁打不上时构建会直接失败（`set -e` + `git apply`），不会静默
  编出未打补丁的包。
