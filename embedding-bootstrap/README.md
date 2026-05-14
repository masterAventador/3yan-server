# sanyan-embedding-bootstrap

三言长期记忆链路的 embedding 微服务：把文本批量转成 1024 维 dense vector，供主服务（new 服务器上的
sanyan bootstrap）做 RAG 检索 + 写入 pgvector。

- 部署单机：**old 服务器**（154.8.162.83 / Lighthouse / Ubuntu 24.04 / 4 核 / 3.6 GiB + 2 GiB swap）
- 模型：BAAI/bge-m3 via DJL + PyTorch engine（mlrepo.djl.ai 预转换 TorchScript，~2.3 GB）
- 端口：8080（Lighthouse + ufw 仅允许 49.233.213.109/32 也就是 new 入站）
- 进程管理：systemd unit `sanyan-embedding.service`（enable + 开机自启）

## 协议契约

POST `/embed`

```
Headers:
  X-Internal-Token: <共享 token>
  Content-Type: application/json

Body:
  { "texts": ["你好世界", "..."] }

Response 200:
  {
    "success": true,
    "code": 0,
    "message": "ok",
    "data": {
      "vectors": [[1024 个 float], ...],
      "dim": 1024,
      "latencyMs": 130
    }
  }
```

错误：

- 401 — `X-Internal-Token` 缺失或不匹配（InternalTokenInterceptor 拦截）
- 503 — 模型未 ready（`/actuator/health` 返回 OUT_OF_SERVICE）
- 5xx — 推理异常或服务异常

## 部署流程

### 一键部署（推荐）

前提：

1. `~/.ssh/config` 配好 `old` + `new` 别名（key auth）
2. 两台机器都已写入共享 token（见下文）
3. old 上 systemd unit 已就位（见下文）

```bash
./deploy.sh
```

脚本会：

1. `mvn clean package -pl embedding-bootstrap -am -DskipTests`
2. `scp target/sanyan-embedding-0.1.0.jar old:/opt/sanyan-embedding/sanyan-embedding.jar`
3. `ssh old 'systemctl restart sanyan-embedding'`
4. 轮询 `/actuator/health` 直到 ready（首次启动 ~3-5 分钟下载 2.3 GB 模型）
5. 从 new 公网调用 `old:8080/embed` 端到端验证

### 首次部署 / 复盘

1. **共享 token 生成与分发**（仅首次）：

   ```bash
   TOKEN=$(openssl rand -hex 32)
   ssh new 'mkdir -p /etc/sanyan && chmod 700 /etc/sanyan'
   ssh new "echo 'EMBEDDING_INTERNAL_TOKEN=$TOKEN' > /etc/sanyan/embedding-token.env && chmod 600 /etc/sanyan/embedding-token.env"
   ssh old 'mkdir -p /etc/sanyan && chmod 700 /etc/sanyan'
   ssh old "echo 'EMBEDDING_INTERNAL_TOKEN=$TOKEN' > /etc/sanyan/embedding-token.env && chmod 600 /etc/sanyan/embedding-token.env"
   ```

   注意：token **绝不** 提交进 git（`.gitignore` 已加 `**/embedding-token*` + `**/*.env`）。

2. **目录就绪**（old）：

   ```bash
   ssh old 'mkdir -p /opt/sanyan-embedding /var/cache/fastembed /var/log/sanyan-embedding'
   ```

3. **systemd unit 写入 old**（`/etc/systemd/system/sanyan-embedding.service`）：

   ```ini
   [Unit]
   Description=Sanyan Embedding Service (BGE-M3 via DJL)
   After=network.target

   [Service]
   Type=simple
   User=root
   EnvironmentFile=/etc/sanyan/embedding-token.env
   Environment=SPRING_PROFILES_ACTIVE=production
   Environment=EMBEDDING_MODEL_CACHE_DIR=/var/cache/fastembed
   ExecStart=/usr/bin/java -Xmx1500m -XX:+UseG1GC -jar /opt/sanyan-embedding/sanyan-embedding.jar
   Restart=on-failure
   RestartSec=10s
   StandardOutput=append:/var/log/sanyan-embedding/server.log
   StandardError=append:/var/log/sanyan-embedding/server.log

   [Install]
   WantedBy=multi-user.target
   ```

   然后：

   ```bash
   ssh old 'systemctl daemon-reload && systemctl enable sanyan-embedding'
   ```

4. **首次启动**：跑 `./deploy.sh`。首次 2.3 GB 模型 + PyTorch native lib 下载约 3-5 分钟，后续重启秒级。

## 运维操作

| 操作 | 命令 |
| --- | --- |
| 健康检查 | `ssh old 'curl -s http://localhost:8080/actuator/health'` |
| 看日志 | `ssh old 'tail -100 /var/log/sanyan-embedding/server.log'` |
| 实时跟踪 | `ssh old 'tail -F /var/log/sanyan-embedding/server.log'` |
| 重启 | `ssh old 'systemctl restart sanyan-embedding'` |
| 看进程内存 | `ssh old 'systemctl status sanyan-embedding'` |
| 端到端测试 | 见下文 |

端到端测试：

```bash
TOKEN=$(ssh new 'cat /etc/sanyan/embedding-token.env | cut -d= -f2')
ssh new "curl -sS -H 'X-Internal-Token: $TOKEN' -H 'Content-Type: application/json' \
  -d '{\"texts\":[\"hello\"]}' http://154.8.162.83:8080/embed"
```

## 故障排查

| 症状 | 诊断 |
| --- | --- |
| 启动后 `/actuator/health` 一直 OUT_OF_SERVICE | tail log；前 3-5 分钟正在下载模型属正常；超过 10 分钟看模型 URL / 网络 / `/var/cache/fastembed` 是否可写 |
| `/embed` 返回 401 | token 不一致；对比 new + old `/etc/sanyan/embedding-token.env` |
| `/embed` 返回 503 + `MODEL_NOT_READY` | 模型加载失败或仍在加载；看 log 是否有 `BGE-M3 模型加载失败` |
| OOMKilled / 进程被 systemd 重启 | `free -h` 看可用物理内存；调小 `-Xmx`（heap）或加 swap；注意 libtorch + 模型在 off-heap，调 heap 不会显著降低 RSS |
| 模型缓存清空 | `rm -rf /var/cache/fastembed/*` 后重启会重新下载，30 GB free 即可 |

## 文件位置

| 文件 | 位置 |
| --- | --- |
| Jar | `/opt/sanyan-embedding/sanyan-embedding.jar` |
| systemd unit | `/etc/systemd/system/sanyan-embedding.service` |
| token EnvironmentFile | `/etc/sanyan/embedding-token.env` (chmod 600) |
| 日志 | `/var/log/sanyan-embedding/server.log` |
| 模型缓存 | `/var/cache/fastembed/` (~2.3 GB) + PyTorch native lib |

## 为什么用 DJL + PyTorch 引擎而不是 ONNX Runtime？

DJL 官方维护的 ONNX Runtime 预转换模型索引（`mlrepo.djl.ai/.../onnxruntime/models.json`）里
**没有 BAAI/bge-m3**（只有 bge-base/bge-large-v1.5 等 768 维变体）。

bge-m3（1024 维）只发布在 DJL 的 pytorch zoo 下，所以 `embedding-core` 用 PyTorch 引擎加载。
PyTorch native lib（libtorch CPU）约 250 MB，在 jar 启动时由 DJL 自动按平台下载到
`EMBEDDING_MODEL_CACHE_DIR`，因此 jar 本身保持 ~50 MB，跨平台部署（本机 mac 集成测试 +
old linux 生产）一份代码。
