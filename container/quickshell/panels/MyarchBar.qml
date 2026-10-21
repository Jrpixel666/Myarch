// The Omarchy-style status bar.
//
// Anchored to the top of the screen on the layer-shell "top" layer, which
// Myarch composites above the launcher's icon grid. The exclusive zone tells
// the compositor to keep that strip free, so a maximised Linux window starts
// below the bar.
import QtQuick
import QtQuick.Layouts
import Quickshell
import Quickshell.Io
import Quickshell.Wayland
import ".."

PanelWindow {
    id: bar

    anchors {
        top: true
        left: true
        right: true
    }
    implicitHeight: Theme.barHeight
    exclusiveZone: Theme.barHeight
    color: "transparent"
    WlrLayershell.layer: WlrLayer.Top
    WlrLayershell.namespace: "myarch-bar"

    property string clock: ""
    property int batteryPercent: -1

    Timer {
        interval: 1000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: bar.clock = Qt.formatDateTime(new Date(), "ddd d MMM  HH:mm")
    }

    FileView {
        id: batteryFile
        path: "/sys/class/power_supply/battery/capacity"
        onLoaded: bar.batteryPercent = parseInt(text()) || -1
    }

    Timer {
        interval: 60000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: batteryFile.reload()
    }

    Rectangle {
        anchors.fill: parent
        anchors.margins: 5
        radius: Theme.radius
        color: Theme.surface

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: Theme.gap + 2
            anchors.rightMargin: Theme.gap + 2
            spacing: Theme.gap

            Text {
                text: "󰣇 myarch"
                color: Theme.accent
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontSize
            }

            Item { Layout.fillWidth: true }

            Text {
                text: bar.clock
                color: Theme.foreground
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontSize
            }

            Item { Layout.fillWidth: true }

            Text {
                visible: bar.batteryPercent >= 0
                text: "󰁹 " + bar.batteryPercent + "%"
                color: bar.batteryPercent < 20 ? Theme.error : Theme.muted
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontSize
            }
        }
    }
}
