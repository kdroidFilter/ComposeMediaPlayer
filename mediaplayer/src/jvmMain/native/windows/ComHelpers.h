#pragma once

// Small RAII helpers around Win32 primitives used throughout the native
// player. Kept header-only to stay dependency-free.

#include <windows.h>
#include <wrl/client.h>
#include <utility>

namespace VideoPlayerUtils {

// RAII wrapper for CRITICAL_SECTION with spin count tuned for tight audio/video
// feed loops (reduces context switches under contention).
class CriticalSection {
public:
    CriticalSection() {
        InitializeCriticalSectionAndSpinCount(&cs_, 4000);
    }
    ~CriticalSection() { DeleteCriticalSection(&cs_); }

    CriticalSection(const CriticalSection&) = delete;
    CriticalSection& operator=(const CriticalSection&) = delete;

    void Enter() { EnterCriticalSection(&cs_); }
    void Leave() { LeaveCriticalSection(&cs_); }

    CRITICAL_SECTION* Raw() { return &cs_; }

private:
    CRITICAL_SECTION cs_{};
};

class ScopedLock {
public:
    explicit ScopedLock(CriticalSection& cs) : cs_(&cs) { cs_->Enter(); }
    ~ScopedLock() { cs_->Leave(); }
    ScopedLock(const ScopedLock&) = delete;
    ScopedLock& operator=(const ScopedLock&) = delete;
private:
    CriticalSection* cs_;
};

// RAII wrapper for Win32 HANDLEs representing events/timers.
class UniqueHandle {
public:
    UniqueHandle() = default;
    explicit UniqueHandle(HANDLE h) : h_(h) {}
    ~UniqueHandle() { Reset(); }

    UniqueHandle(const UniqueHandle&) = delete;
    UniqueHandle& operator=(const UniqueHandle&) = delete;

    UniqueHandle(UniqueHandle&& other) noexcept : h_(other.h_) { other.h_ = nullptr; }
    UniqueHandle& operator=(UniqueHandle&& other) noexcept {
        if (this != &other) {
            Reset();
            h_ = other.h_;
            other.h_ = nullptr;
        }
        return *this;
    }

    void Reset(HANDLE h = nullptr) {
        if (h_ && h_ != INVALID_HANDLE_VALUE) CloseHandle(h_);
        h_ = h;
    }

    HANDLE Get() const { return h_; }
    HANDLE Release() { HANDLE h = h_; h_ = nullptr; return h; }
    explicit operator bool() const { return h_ != nullptr && h_ != INVALID_HANDLE_VALUE; }

private:
    HANDLE h_ = nullptr;
};

} // namespace VideoPlayerUtils
