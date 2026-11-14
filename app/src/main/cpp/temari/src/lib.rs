//! temari — Apple Music FairPlay SAMPLE-AES sample decryption library.
//!
//! 以 FFI 为主的纯 std 库,编译为 rlib + cdylib(`libtemari.so` / `.dll` / `.dylib`)。
//! 提供:
//!   * `rounds` / `rounds_gen` — 解密核心(round 链 R1→R2→R3→CBC)
//!   * `stream` — 流式解密(增量提交 + 有序取回,自适应攒批并行)
//!   * `template` — 模板加载(二进制测试向量 + 40020 JSON 解析,不负责网络)
//!   * `ffi` — cdylib 导出(供 Python ctypes / Go purego 进程内调用)
//!
//! 网络服务(10020 TCP / gRPC Decrypt)与 CLI 工具见 `tools/temari-decrypt`(独立 crate)。
pub mod ffi;
mod pool;
pub mod rounds;
pub mod rounds_gen;
pub mod stream;
pub mod template;
