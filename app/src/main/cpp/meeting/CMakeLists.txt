add_executable(meeting_probe meeting_probe.cpp)
target_compile_features(meeting_probe PRIVATE cxx_std_17)
target_compile_options(meeting_probe PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(meeting_probe PRIVATE "LINKER:--exclude-libs,ALL")
target_link_libraries(meeting_probe PRIVATE nemo_speech_asr)
if(ANDROID)
    target_link_libraries(meeting_probe PRIVATE log dl)
endif()
set_target_properties(meeting_probe PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

add_library(dictai_meeting SHARED meeting_jni.cpp meeting_native_logic.cpp)
target_compile_features(dictai_meeting PRIVATE cxx_std_17)
target_compile_options(dictai_meeting PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(dictai_meeting PRIVATE
    "LINKER:--exclude-libs,ALL"
    "LINKER:--version-script,${CMAKE_CURRENT_SOURCE_DIR}/meeting_jni.exports"
    "LINKER:-z,max-page-size=16384")
target_link_libraries(dictai_meeting PRIVATE nemo_speech_asr)
if(ANDROID)
    target_link_libraries(dictai_meeting PRIVATE log dl)
endif()
set_target_properties(dictai_meeting PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
