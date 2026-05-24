#include "VibrationEngine.h"

#include <algorithm>
#include <cmath>
#include <mutex>

namespace {
std::mutex g_mu;


constexpr float kMinM = 0.25f;
constexpr float kMaxM = 2.50f;

static VibrationParams mapDistance(float meters) noexcept
{
    if (!std::isfinite(meters) || meters <= 0.0f || meters > kMaxM) {
        return VibrationParams{false, 0, 0};
    }

    const float clamped = std::clamp(meters, kMinM, kMaxM);
    const float t = (clamped - kMinM) / (kMaxM - kMinM);

    const int amp = static_cast<int>(std::lround(255.0f - t * (255.0f - 60.0f)));
    const int interval = static_cast<int>(std::lround(80.0f + t * (600.0f - 80.0f)));

    return VibrationParams{true, std::clamp(amp, 1, 255), std::clamp(interval, 50, 1000)};
}

VibrationEngine &VibrationEngine::instance()
{
    static VibrationEngine inst;
    return inst;
}

void VibrationEngine::updateDistanceMeters(float meters) noexcept
{
    std::lock_guard<std::mutex> lock(g_mu);
    m_lastMeters = meters;
    m_params = mapDistance(meters);
}

VibrationParams VibrationEngine::params() const noexcept
{
    std::lock_guard<std::mutex> lock(g_mu);
    return m_params;
}

