# Qinghe - 安卓弱网测试工具

一款基于VPNService实现的Android弱网测试工具，支持模拟各种网络环境。

## 功能特性

- 基于VPNService实现核心弱网模拟功能
- 支持选择指定应用进行弱网测试
- 支持自定义弱网配置（延时、抖动、带宽限制、丢包等）
- 配置本地永久存储，支持编辑和删除
- 悬浮窗快速切换配置，即时生效
- 兼容Android 5.0至最新系统

## 配置参数

| 参数 | 单位 | 默认值 | 说明 |
|------|------|--------|------|
| 上行/下行延时 | ms | 0 | 网络延迟 |
| 上行/下行延时抖动 | ms | 0 | 延迟波动范围 |
| 上行/下行带宽 | kbps | 0 | 带宽限制(0为不限制) |
| 上行/下行随机丢包 | % | 0 | 随机丢包概率(0-100) |
| 上行/下行连续丢包放行时长 | ms | 0 | 连续丢包模式放行时间 |
| 上行/下行连续丢包丢包时长 | ms | 0 | 连续丢包模式丢包时间 |
| 弱网控制协议 | - | 3 | 1=TCP, 2=UDP, 3=全部 |

## 构建

```bash
./gradlew assembleDebug
```

## CI/CD

项目配置了GitHub Actions自动构建：
- 优先编译签名正式版APK
- 密钥校验失败或无密钥时自动编译测试版APK

### GitHub Secrets配置

| Secret | 说明 |
|--------|------|
| KEYSTORE_BASE64 | 签名文件Base64编码 |
| KEYSTORE_PASSWORD | 密钥库密码 |
| KEY_ALIAS | 密钥别名 |
| KEY_PASSWORD | 密钥密码 |

## 关于

- 应用名称：Qinghe
- 版本：v1.0
- 作者：Qinghe
- 永久免费开源
- GitHub：[Qinghe-Team/NetTest](https://github.com/Qinghe-Team/NetTest)
- 作者QQ：3686072365
