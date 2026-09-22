cmake_minimum_required(VERSION 3.18.1)

# 1. 先定义所有的极致压缩参数（必须在 add_subdirectory 之前！）
# -Oz: 比 -Os 更激进的体积压缩 (Clang/NDK 支持)
# -fvisibility=hidden: 隐藏符号，大幅减小体积
# -fno-exceptions -fno-rtti: 移除 C++ 异常和类型信息（对 C++ 体积影响巨大）
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Oz -ffunction-sections -fdata-sections -fvisibility=hidden")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Oz -ffunction-sections -fdata-sections -fvisibility=hidden -fno-exceptions -fno-rtti")
add_compile_options(
        -Os                     # 针对体积优化
        -fno-rtti               # 禁用运行时类型信息
        -fno-exceptions         # 禁用 C++ 异常
        -fno-unwind-tables      # 移除堆栈展开表
        -fno-stack-protector    # (可选) 移除栈保护，进一步减小体积
        -ffunction-sections     # 函数分段
        -fdata-sections         # 数据分段
        -fvisibility=hidden     # 隐藏内部符号

)
add_link_options(
        -Wl,--gc-sections       # 移除未使用的段
        -Wl,--strip-all         # 剥离所有符号
        -Wl,-z,max-page-size=16384
)

# 链接器优化：
# --gc-sections: 删除未使用的代码段
# --icf=all: 合并完全相同的代码（Gold/LLD 链接器特性，很强）
# -s: 剥离符号表 (Strip)，相当于运行了 strip 命令
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--icf=all -Wl,-s")

# 2. 包含头文件路径
include_directories(${CMAKE_SOURCE_DIR}/Lrc ${CMAKE_SOURCE_DIR}/MflacCrypto ${CMAKE_SOURCE_DIR}/KgCrypto)

# 3. 最后添加子目录（此时它们会继承上面的优化参数）
add_subdirectory(Lrc)
add_subdirectory(MflacCrypto)
add_subdirectory(KgCrypto)

