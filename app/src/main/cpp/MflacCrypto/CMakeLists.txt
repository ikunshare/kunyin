project("MflacCrypto")

add_library(MflacCrypto SHARED
        MflacCrypto.cpp
)
target_link_libraries(MflacCrypto z)