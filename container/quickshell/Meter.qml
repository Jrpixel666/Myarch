// A labelled progress bar, shared by Myarch's widgets.
import QtQuick
import QtQuick.Layouts

ColumnLayout {
    id: root

    property string label: ""
    property real value: 0

    spacing: 3

    RowLayout {
        Layout.fillWidth: true

        Text {
            text: root.label
            color: Theme.muted
            font.family: Theme.fontFamily
            font.pixelSize: 11
        }

        Item { Layout.fillWidth: true }

        Text {
            text: Math.round(root.value) + "%"
            color: Theme.foreground
            font.family: Theme.fontFamily
            font.pixelSize: 11
        }
    }

    Rectangle {
        Layout.fillWidth: true
        implicitHeight: 6
        radius: 3
        color: Qt.darker(Theme.surface, 1.4)

        Rectangle {
            width: parent.width * Math.min(1, Math.max(0, root.value / 100))
            height: parent.height
            radius: parent.radius
            color: root.value > 85 ? Theme.error
                 : root.value > 60 ? Theme.warning
                 : Theme.accent

            Behavior on width {
                NumberAnimation { duration: 400; easing.type: Easing.OutCubic }
            }
        }
    }
}
