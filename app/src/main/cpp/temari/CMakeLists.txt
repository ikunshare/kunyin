project(TemariJni LANGUAGES CXX)

add_library(TemariJni SHARED TemariJni.cpp)
target_link_libraries(TemariJni PRIVATE dl)
