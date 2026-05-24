cmake_minimum_required(VERSION 3.16)

project(BlindAssist VERSION 0.1 LANGUAGES CXX)

set(CMAKE_AUTOUIC ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(QT NAMES Qt6 Qt5 REQUIRED COMPONENTS Core Quick)
find_package(Qt${QT_VERSION_MAJOR} REQUIRED COMPONENTS Core Quick)

set(PROJECT_SOURCES
        main.cpp
        qml.qrc
        cpp/core/DistanceModel.h
        cpp/core/DistanceModel.cpp
        cpp/vibration/VibrationEngine.h
        cpp/vibration/VibrationEngine.cpp
        cpp/jni/JniBridge.h
        cpp/jni/JniBridge.cpp
)

if(${QT_VERSION_MAJOR} GREATER_EQUAL 6)
    qt_add_executable(BlindAssist
        MANUAL_FINALIZATION
        ${PROJECT_SOURCES}
    )
    if(COMMAND qt_policy)
        qt_policy(SET QTP0002 NEW)
    endif()
else()
    if(ANDROID)
        add_library(BlindAssist SHARED
            ${PROJECT_SOURCES}
        )
    endif()

    qt5_create_translation(QM_FILES ${CMAKE_SOURCE_DIR})
endif()

target_link_libraries(BlindAssist
  PRIVATE Qt${QT_VERSION_MAJOR}::Core Qt${QT_VERSION_MAJOR}::Quick)

target_include_directories(BlindAssist PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp
)

if(ANDROID AND QT_VERSION_MAJOR EQUAL 6)
    set_property(TARGET BlindAssist PROPERTY
        QT_ANDROID_PACKAGE_NAME
        "com.example.blindassist"
    )
    set_property(TARGET BlindAssist PROPERTY
        QT_ANDROID_PACKAGE_SOURCE_DIR
        ${CMAKE_CURRENT_SOURCE_DIR}/android
    )
endif()

if(${QT_VERSION} VERSION_LESS 6.1.0)
  set(BUNDLE_ID_OPTION MACOSX_BUNDLE_GUI_IDENTIFIER com.example.blindassist)
endif()
set_target_properties(BlindAssist PROPERTIES
    ${BUNDLE_ID_OPTION}
    MACOSX_BUNDLE_BUNDLE_VERSION ${PROJECT_VERSION}
    MACOSX_BUNDLE_SHORT_VERSION_STRING ${PROJECT_VERSION_MAJOR}.${PROJECT_VERSION_MINOR}
    MACOSX_BUNDLE TRUE
    WIN32_EXECUTABLE TRUE
)

include(GNUInstallDirs)
install(TARGETS BlindAssist
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR})

if(QT_VERSION_MAJOR EQUAL 6)
    qt_import_qml_plugins(BlindAssist)
    qt_finalize_executable(BlindAssist)
endif()
