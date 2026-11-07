# jenkins-atom-artifact-promote

把制品从 staging 仓库提升到 release 仓库（也可跨实例）。

artifact-promotion 插件在 Artifactory 内部走它自己的提升 API；能搬的部分是「这个文件从 staging 到
release，内容不变」。用普通 HTTP 做，意味着 Artifactory、Nexus 以及任何支持 GET/PUT 的仓库都能用。

**不信任 201**：`verify=true` 时会把目标回读一遍、比较 sha256，不一致就失败——制品仓库接受写入却存错，
在真实环境里并不罕见（代理缓存、分区写满、路径大小写差异）。

- 来源读不到 → `USER`（提示查路径与仓库名）
- 目标 401/403 → `USER`（提示 token 需要 deploy 权限）
- 回读不一致 → `THIRD_PART`

```bash
mvn verify
```
