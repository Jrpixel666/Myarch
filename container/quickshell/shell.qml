// The Myarch Quickshell session.
//
// Everything here is a layer-shell surface, which Myarch draws on the Android
// launcher's home screen rather than giving it a task of its own. That is what
// makes an Omarchy bar and Omarchy widgets feel like part of the phone's home
// screen instead of a Linux window floating over it.
import Quickshell
import "panels"

ShellRoot {
    MyarchBar {}
    MyarchWidgets {}
}
