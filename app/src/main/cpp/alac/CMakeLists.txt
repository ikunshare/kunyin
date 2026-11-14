project(AlacJni LANGUAGES C CXX)

# ffmpeg 只保留 libavcodec + libavutil 的最小构建（仅 ALAC 解码器），
# 静态库按 ABI 预编译在 ffmpeg/lib/<abi>/ 下，构建脚本见 ffmpeg/build-alac.sh。
set(FFMPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../ffmpeg)

add_library(ffmpeg_alac STATIC IMPORTED)
set_target_properties(ffmpeg_alac PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_DIR}/lib/${ANDROID_ABI}/libavcodec.a)

add_library(ffmpeg_util STATIC IMPORTED)
set_target_properties(ffmpeg_util PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_DIR}/lib/${ANDROID_ABI}/libavutil.a)

add_library(AlacJni SHARED AlacJni.cpp AlacDecoderCore.cpp)
target_include_directories(AlacJni PRIVATE ${FFMPEG_DIR}/include)
target_link_libraries(AlacJni PRIVATE ffmpeg_alac ffmpeg_util log m)
