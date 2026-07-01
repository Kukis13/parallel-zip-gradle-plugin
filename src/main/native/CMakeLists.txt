cmake_minimum_required(VERSION 3.10)
project(pzip_native C)

# libdeflate_static gets linked into our shared library below, so it must be
# built as position-independent code too (no-op on Windows).
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LIBDEFLATE_BUILD_SHARED_LIB OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_GZIP OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(libdeflate)

# CMake's own FindJNI additionally requires the AWT native headers/libs,
# which don't exist in headless JDKs and aren't needed for a plain JNI
# extension. Locate just the jni.h / jni_md.h headers directly instead.
if(NOT JAVA_HOME AND DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME $ENV{JAVA_HOME})
endif()
find_path(PZIP_JNI_INCLUDE_DIR jni.h PATHS "${JAVA_HOME}/include")
if(WIN32)
    set(PZIP_JNI_MD_SUBDIR win32)
elseif(APPLE)
    set(PZIP_JNI_MD_SUBDIR darwin)
else()
    set(PZIP_JNI_MD_SUBDIR linux)
endif()
find_path(PZIP_JNI_MD_INCLUDE_DIR jni_md.h PATHS "${JAVA_HOME}/include/${PZIP_JNI_MD_SUBDIR}")
if(NOT PZIP_JNI_INCLUDE_DIR OR NOT PZIP_JNI_MD_INCLUDE_DIR)
    message(FATAL_ERROR "Could not locate JNI headers under JAVA_HOME (${JAVA_HOME}); set -DJAVA_HOME or the JAVA_HOME env var")
endif()

add_library(pzip_libdeflate SHARED jni/pzip_libdeflate.c)
target_include_directories(pzip_libdeflate PRIVATE ${PZIP_JNI_INCLUDE_DIR} ${PZIP_JNI_MD_INCLUDE_DIR} libdeflate)
target_link_libraries(pzip_libdeflate PRIVATE libdeflate::libdeflate_static)

# Land the built library directly where the Gradle build expects to pick it
# up from, keyed by OS + arch so multiple platforms' binaries can coexist.
install(TARGETS pzip_libdeflate
        RUNTIME DESTINATION "${PZIP_NATIVE_OUT_DIR}"
        LIBRARY DESTINATION "${PZIP_NATIVE_OUT_DIR}")
