// Default palette, replaced whenever a theme is chosen in the Myarch app.
//
// Do not edit: the app rewrites this file. Add your own colours to a theme
// directory and import it from Settings > Themes instead.
pragma Singleton

import QtQuick

QtObject {
    readonly property string name: "Tokyo Night"
    readonly property bool light: false
    readonly property color background: "#1a1b26"
    readonly property color surface: "#24283b"
    readonly property color foreground: "#c0caf5"
    readonly property color muted: "#565f89"
    readonly property color accent: "#7aa2f7"
    readonly property color accentAlt: "#bb9af7"
    readonly property color warning: "#e0af68"
    readonly property color error: "#f7768e"
    readonly property color success: "#9ece6a"
    readonly property var ansi: [
        "#15161e", "#f7768e", "#9ece6a", "#e0af68",
        "#7aa2f7", "#bb9af7", "#7dcfff", "#a9b1d6",
        "#414868", "#f7768e", "#9ece6a", "#e0af68",
        "#7aa2f7", "#bb9af7", "#7dcfff", "#c0caf5"
    ]

    // Shared metrics so widgets line up with each other.
    readonly property int radius: 14
    readonly property int gap: 10
    readonly property int barHeight: 38
    readonly property string fontFamily: "JetBrainsMono Nerd Font"
    readonly property int fontSize: 13
}
