// The home screen widget layer.
//
// Widgets created in the app's builder are listed in WidgetRegistry.qml and
// loaded here by name. The panel claims no exclusive zone and sits just below
// the bar, so the launcher's own icons and pages stay usable around it.
import QtQuick
import QtQuick.Layouts
import Quickshell
import Quickshell.Wayland
import ".."

PanelWindow {
    id: layer

    anchors {
        top: true
        left: true
        right: true
    }
    margins.top: Theme.barHeight + Theme.gap
    implicitHeight: Math.max(1, flow.implicitHeight + 2 * Theme.gap)
    color: "transparent"
    WlrLayershell.layer: WlrLayer.Top
    WlrLayershell.namespace: "myarch-widgets"
    // Without this the whole strip would swallow taps meant for the launcher
    // underneath, even where there is no widget.
    WlrLayershell.keyboardFocus: WlrKeyboardFocus.None

    Flow {
        id: flow

        anchors.fill: parent
        anchors.margins: Theme.gap
        spacing: Theme.gap

        Repeater {
            model: WidgetRegistry.widgets

            Loader {
                required property var modelData

                // A widget that fails to load should not take the shell down
                // with it, so failures are reported and skipped.
                source: "../widgets/" + modelData.component + ".qml"
                asynchronous: true
                onStatusChanged: {
                    if (status === Loader.Error) {
                        console.warn("myarch: widget", modelData.component, "failed to load");
                    }
                }
            }
        }
    }

    Text {
        anchors.centerIn: parent
        visible: WidgetRegistry.widgets.length === 0
        text: "No widgets yet — add one from Build › Widgets"
        color: Theme.muted
        font.family: Theme.fontFamily
        font.pixelSize: 12
    }
}
