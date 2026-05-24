import QtQuick 2.15
import QtQuick.Window 2.15

Window {
    width: Screen.width
    height: Screen.height
    visible: true
    title: qsTr("Помощник по зрению")
    color: "#1a1a1a"

    Flickable {
        id: flick
        anchors.fill: parent
        contentWidth: width
        contentHeight: col.height + 48
        clip: true

        Column {
            id: col
            x: Math.max(16, (flick.width - width) / 2)
            width: Math.min(flick.width - 32, 560)
            spacing: 14

            Text {
                width: col.width
                color: "#f2f2f2"
                text: "Расстояние (м): " + Number(DistanceModel.distanceMeters).toFixed(2)
                font.pixelSize: 24
                wrapMode: Text.WordWrap
            }

            Text {
                width: col.width
                color: DistanceModel.distanceSpeechEnabled ? "#a8d4a8" : "#c9a0a0"
                text: DistanceModel.distanceSpeechEnabled
                    ? "Озвучка расстояния: включена (голосом: «выключи озвучку расстояния»)"
                    : "Озвучка расстояния: выключена (голосом: «включи озвучку расстояния»)"
                font.pixelSize: 15
                wrapMode: Text.WordWrap
            }

            Text {
                width: col.width
                color: "#d0d0d0"
                text: DistanceModel.statusText
                opacity: 0.95
                wrapMode: Text.WordWrap
            }
        }
    }
}
