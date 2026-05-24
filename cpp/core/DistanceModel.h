#pragma once

#include <QObject>
#include <QString>

class DistanceModel final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(float distanceMeters READ distanceMeters NOTIFY distanceMetersChanged)
    Q_PROPERTY(QString statusText READ statusText NOTIFY statusTextChanged)
    Q_PROPERTY(bool distanceSpeechEnabled READ distanceSpeechEnabled NOTIFY distanceSpeechEnabledChanged)

public:
    float distanceMeters() const noexcept { return m_distanceMeters; }
    QString statusText() const { return m_statusText; }
    bool distanceSpeechEnabled() const noexcept { return m_distanceSpeechEnabled; }

    void setDistanceMetersFromAnyThread(float meters);
    void setStatusTextFromAnyThread(QString text);
    void setDistanceSpeechEnabledFromAnyThread(bool enabled);

signals:
    void distanceMetersChanged();
    void statusTextChanged();
    void distanceSpeechEnabledChanged();

private:
    friend DistanceModel *createDistanceModel(QObject *parent);
    explicit DistanceModel(QObject *parent = nullptr);

    void setDistanceMetersOnGuiThread(float meters);
    void setStatusTextOnGuiThread(QString text);
    void setDistanceSpeechEnabledOnGuiThread(bool enabled);

    float m_distanceMeters = 0.0f;
    QString m_statusText;
    bool m_distanceSpeechEnabled = true;
};

DistanceModel *createDistanceModel(QObject *parent = nullptr);
DistanceModel *distanceModelInstance() noexcept;
void setDistanceModelInstance(DistanceModel *instance) noexcept;
