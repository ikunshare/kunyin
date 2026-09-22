project("Lrc")

add_library(Lrc SHARED Lrc.cpp)
target_link_libraries(Lrc z)