cmake_minimum_required(VERSION 3.22.1)
project(transcribe_jni LANGUAGES CXX)

if(NOT DEFINED TRANSCRIBE_SOURCE_DIR OR NOT EXISTS "${TRANSCRIBE_SOURCE_DIR}/include/transcribe.h")
    message(FATAL_ERROR "TRANSCRIBE_SOURCE_DIR must point to transcribe.cpp at the pinned source revision")
endif()

if(NOT DEFINED TRANSCRIBE_LIBRARY_DIR OR NOT EXISTS "${TRANSCRIBE_LIBRARY_DIR}/libtranscribe.so")
    message(FATAL_ERROR "TRANSCRIBE_LIBRARY_DIR must contain libtranscribe.so")
endif()

add_library(transcribe SHARED IMPORTED)
set_target_properties(transcribe PROPERTIES
    IMPORTED_LOCATION "${TRANSCRIBE_LIBRARY_DIR}/libtranscribe.so"
)

add_library(transcribe_jni SHARED transcribe_jni.cpp)
target_compile_features(transcribe_jni PRIVATE cxx_std_20)
target_include_directories(transcribe_jni PRIVATE "${TRANSCRIBE_SOURCE_DIR}/include")
target_link_libraries(transcribe_jni PRIVATE transcribe log)
target_link_options(transcribe_jni PRIVATE "-Wl,-z,max-page-size=16384")
