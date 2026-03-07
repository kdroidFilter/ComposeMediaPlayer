#include "Utils.h"
#include <thread>
#include <algorithm>

namespace VideoPlayerUtils {

void PreciseSleepHighRes(double ms) {
    if (ms <= 0.1)
        return;

    // Use a single static timer for all sleep operations
    static HANDLE hTimer = CreateWaitableTimer(nullptr, TRUE, nullptr);
    if (!hTimer) {
        std::this_thread::sleep_for(std::chrono::duration<double, std::milli>(ms));
        return;
    }

    LARGE_INTEGER liDueTime;
    liDueTime.QuadPart = -static_cast<LONGLONG>(ms * 10000.0);
    SetWaitableTimer(hTimer, &liDueTime, 0, nullptr, nullptr, FALSE);
    WaitForSingleObject(hTimer, INFINITE);
}

} // namespace VideoPlayerUtils