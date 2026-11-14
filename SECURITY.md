# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| `0.1.0-beta` | Yes |
| `< 0.1.0-beta` | No |

## Reporting a Vulnerability

If you discover a denial-of-service vector, Netty channel leak, JNI memory safety bug, or packet-parsing vulnerability in Celeris:

1. **Do not** open a public GitHub issue with exploit details or reproduction payloads.
2. Report privately via [GitHub Security Advisories](https://github.com/fy2ne/Celeris/security/advisories/new).
3. Include affected platform (Paper, Velocity, Fabric, NeoForge), Minecraft version, engine mode (`rust-native` vs `java-engine`), and minimal reproduction steps.

## Hardening Guarantees

- Unauthenticated/handshake channels bypass priority queuing until login state completes.
- Express queues enforce bounded byte & packet limits with automatic fallback to FIFO when saturated.
- Native JNI buffers validate slice bounds before crossing the FFI boundary.
