#pragma once

#include <jni.h>

namespace jni_bridge {
void onDistanceUpdate(float meters);
void onStatusUpdate(const char *utf8);
}

