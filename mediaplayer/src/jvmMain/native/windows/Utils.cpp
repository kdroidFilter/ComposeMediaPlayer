#include "Utils.h"
#include <thread>
#include <algorithm>

namespace VideoPlayerUtils {

// Creates a high-resolution waitable timer (100 µs granularity on Windows 10
// 1803+) without touching the global timer period. Falls back to a classic
// waitable timer, then to std::this_thread::sleep_for, if unavailable.
static HANDLE CreateHighResolutionTimer() {
    using CreateExPtr = HANDLE (WINAPI*)(LPSECURITY_ATTRIBUTES, LPCWSTR, DWORD, DWORD);
    static const CreateExPtr pCreateEx = []() -> CreateExPtr {
        HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
        return kernel ? reinterpret_cast<CreateExPtr>(
            GetProcAddress(kernel, "CreateWaitableTimerExW")) : nullptr;
    }();

    // 0x00000002 = CREATE_WAITABLE_TIMER_HIGH_RESOLUTION (Win10 1803+)
    constexpr DWORD kHighRes = 0x00000002;
    if (pCreateEx) {
        HANDLE h = pCreateEx(nullptr, nullptr, kHighRes, TIMER_ALL_ACCESS);
        if (h) return h;
    }
    return CreateWaitableTimerW(nullptr, TRUE, nullptr);
}

void PreciseSleepHighRes(double ms) {
    if (ms <= 0.1)
        return;

    // Thread-local timer: no sharing between threads, no locking overhead.
    thread_local HANDLE hTimer = CreateHighResolutionTimer();
    if (!hTimer) {
        std::this_thread::sleep_for(std::chrono::duration<double, std::milli>(ms));
        return;
    }

    LARGE_INTEGER liDueTime;
    liDueTime.QuadPart = -static_cast<LONGLONG>(ms * 10000.0);
    if (SetWaitableTimer(hTimer, &liDueTime, 0, nullptr, nullptr, FALSE)) {
        WaitForSingleObject(hTimer, INFINITE);
    } else {
        std::this_thread::sleep_for(std::chrono::duration<double, std::milli>(ms));
    }
}

} // namespace VideoPlayerUtils
