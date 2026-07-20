cmake_minimum_required(VERSION 3.22.1)
project(bonsai_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 2023年以降の arm64 端末向け。llama.cpp は実行時にCPU機能を判定するので
# これより古い端末でも起動自体はする。
add_compile_options(-march=armv8.7a)

# CI が clone してくるツリー（.gitignore 済み）
if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp が見つかりません。CI の 'Fetch llama.cpp' ステップ、"
        "またはローカルなら app/src/main/cpp/llama.cpp へ clone してください。")
endif()

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(bonsai_jni SHARED llama_jni.cpp)

find_library(log-lib log)

target_link_libraries(bonsai_jni
    llama
    ggml
    android
    ${log-lib}
)
