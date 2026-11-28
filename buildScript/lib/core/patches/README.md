# Rift 核心补丁（patches/）

官方 sing-box 源码由 `buildScript/lib/core/get_source.sh` 在构建时从上游
克隆（验 tag 与 commit，不改官方源码）。这里的 `.patch` 文件在克隆、
校验通过后依次打到 `../sing-box` 上，再编译。补丁与 libcore 内的 XHTTP graft
一起构成 Rift 的核心改动（目前共一个 .patch 文件）。

## reality-client-version-183.patch

- 改动：`common/tls/reality_client.go`（sing-box v1.15.0-alpha.15），仅一行：
  `hello.SessionId[2] = 2` → `= 3`，即 REALITY 客户端版本号 1.8.2 → 1.8.3。
- 原因：sing-box 的 REALITY 客户端握手里写死的版本号是 1.8.2（实现
  REALITY 时抄自当时 Xray 的版本，之后从未跟进）；设了 minclientver
  1.8.3 的 Xray 服务端会把 1.8.2 客户端静默回落到真网站，客户端拿到
  真站证书对不上公钥，报 reality verification failed。版本号只用于
  服务端的门槛比较，不参与任何加密计算；老节点服务端没设门槛，不受
  影响。
- 验证：待用户实机测 Hinet 家宽节点（VLESS+XHTTP+Reality，服务端
  Xray-core 最新版，minclientver 1.8.3）。

## 已删的补丁

- reality-mlkem-keep-share.patch：2026-11-28 随 alpha.15 升级删除。
  上游 alpha.14 的 39501357a5 修了 sing-box #4520（不再从 ClientHello
  里去掉 X25519MLKEM768）；在 alpha.15 源码里逐行核实：`ClientHandshake`
  只做一次 `BuildHandshakeState()`，没有任何对 group/share 做
  `common.Filter` 的代码，旧补丁在 alpha.15 上也已打不上。

## 维护规则

- 每次升 SINGBOX_VERSION 都要确认补丁能打上、且上游是否已把版本号
  跟进到 ≥1.8.3（version-183）。
- 上游修了（补丁打不上或已无必要）就删 `.patch` 文件与
  `get_source.sh` 里的打补丁段，不要留死代码。
- 补丁打不上时构建会直接失败（`set -e` + `git apply`），不会静默
  编出未打补丁的包。
