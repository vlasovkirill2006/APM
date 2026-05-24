#pragma once

#include <cstdint>

struct VibrationParams final {
    bool active = false;
    std::int32_t amplitude = 0;
    std::int32_t intervalMs = 0;
};

class VibrationEngine final
{
public:
    static VibrationEngine &instance();

    void updateDistanceMeters(float meters) noexcept;
    VibrationParams params() const noexcept;

private:
    VibrationEngine() = default;

    // State
    float m_lastMeters = 0.0f;
    VibrationParams m_params{};
};

