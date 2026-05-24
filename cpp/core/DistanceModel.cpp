#include "DistanceModel.h"

#include <atomic>
#include <QCoreApplication>
#include <QMetaObject>
#include <QThread>
#include <QtGlobal>

namespace {
std::atomic<DistanceModel *> g_instance{nullptr};
}

DistanceModel::DistanceModel(QObject *parent)
    : QObject(parent)
{
    m_statusText = QString::fromUtf8(u8"Ожидание ARCore…");
}

DistanceModel *createDistanceModel(QObject *parent)
{
    return new DistanceModel(parent);
}

DistanceModel *distanceModelInstance() noexcept
{
    return g_instance.load(std::memory_order_acquire);
}

void setDistanceModelInstance(DistanceModel *instance) noexcept
{
    g_instance.store(instance, std::memory_order_release);
}

void DistanceModel::setDistanceMetersFromAnyThread(float meters)
{
    if (QThread::currentThread() == QCoreApplication::instance()->thread()) {
        setDistanceMetersOnGuiThread(meters);
        return;
    }
    QMetaObject::invokeMethod(
        this,
        [this, meters]() { setDistanceMetersOnGuiThread(meters); },
        Qt::QueuedConnection);
}

void DistanceModel::setStatusTextFromAnyThread(QString text)
{
    if (QThread::currentThread() == QCoreApplication::instance()->thread()) {
        setStatusTextOnGuiThread(std::move(text));
        return;
    }
    QMetaObject::invokeMethod(
        this,
        [this, text = std::move(text)]() mutable { setStatusTextOnGuiThread(std::move(text)); },
        Qt::QueuedConnection);
}

void DistanceModel::setDistanceSpeechEnabledFromAnyThread(bool enabled)
{
    if (QThread::currentThread() == QCoreApplication::instance()->thread()) {
        setDistanceSpeechEnabledOnGuiThread(enabled);
        return;
    }
    QMetaObject::invokeMethod(
        this,
        [this, enabled]() { setDistanceSpeechEnabledOnGuiThread(enabled); },
        Qt::QueuedConnection);
}

void DistanceModel::setDistanceMetersOnGuiThread(float meters)
{
    if (!qIsFinite(meters) || meters < 0.0f) return;
    if (qFuzzyCompare(m_distanceMeters, meters)) return;
    m_distanceMeters = meters;
    emit distanceMetersChanged();
}

void DistanceModel::setStatusTextOnGuiThread(QString text)
{
    if (m_statusText == text) return;
    m_statusText = std::move(text);
    emit statusTextChanged();
}

void DistanceModel::setDistanceSpeechEnabledOnGuiThread(bool enabled)
{
    if (m_distanceSpeechEnabled == enabled) return;
    m_distanceSpeechEnabled = enabled;
    emit distanceSpeechEnabledChanged();
}
