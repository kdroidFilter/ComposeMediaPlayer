#pragma once

#include <windows.h>
#include <chrono>

namespace VideoPlayerUtils {

/**
 * @brief Gets the current time in milliseconds.
 * @return Current time in milliseconds.
 */
inline ULONGLONG GetCurrentTimeMs() {
    return static_cast<ULONGLONG>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

/**
 * @brief Performs a high-resolution sleep for the specified duration.
 * @param ms Sleep duration in milliseconds.
 */
void PreciseSleepHighRes(double ms);

} // namespace VideoPlayerUtils