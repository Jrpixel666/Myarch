package dev.myarch.runtime.pacman

/**
 * The package groups Myarch installs.
 *
 * Everything here comes from Arch Linux ARM's official repositories. Quickshell
 * itself is only in the AUR, so it is built by
 * `container/scripts/build-quickshell.sh` from the dependencies listed in
 * [QUICKSHELL_BUILD] rather than installed with pacman -- see
 * docs/OMARCHY.md for why and what the alternatives are.
 */
public object PackageSets {

    /** The minimum for a usable container plus a graphical session. */
    public val OMARCHY_CORE: List<String> = listOf(
        // Toolchain, needed to build anything from the AUR (Quickshell).
        "base-devel",
        "git",
        "cmake",
        "ninja",
        "pkgconf",
        // Shell and editor.
        "fish",
        "neovim",
        "starship",
        // Terminals. Omarchy ships Alacritty; foot is a much smaller fallback
        // and starts faster on a phone.
        "alacritty",
        "foot",
        // Qt 6, which Quickshell is built on.
        "qt6-base",
        "qt6-declarative",
        "qt6-wayland",
        "qt6-svg",
        "qt6-shadertools",
        "qt6-5compat",
        "qt6-imageformats",
        "qt6-multimedia",
        // Wayland plumbing and software rendering.
        "wayland",
        "wayland-utils",
        "libxkbcommon",
        "libxkbcommon-x11",
        "xkeyboard-config",
        "mesa",
        "vulkan-swrast",
        "wl-clipboard",
        // Fonts: without these every window renders boxes.
        "ttf-jetbrains-mono-nerd",
        "noto-fonts",
        "noto-fonts-emoji",
        "ttf-firacode-nerd",
        // Omarchy's own day-to-day tools.
        "btop",
        "fastfetch",
        "lazygit",
        "fzf",
        "ripgrep",
        "fd",
        "bat",
        "eza",
        "zoxide",
        "jq",
        "unzip",
        "wget",
        "curl",
        "openssh",
        "man-db",
        "less",
        "tree",
    )

    /** What `build-quickshell.sh` needs in order to compile Quickshell. */
    public val QUICKSHELL_BUILD: List<String> = listOf(
        "cmake",
        "ninja",
        "pkgconf",
        "qt6-base",
        "qt6-declarative",
        "qt6-wayland",
        "qt6-svg",
        "qt6-shadertools",
        "qt6-tools",
        "cli11",
        "jemalloc",
        "libdrm",
        "libxcb",
        "libpipewire",
        "google-breakpad",
    )

    /** Optional extras offered in the app's package browser. */
    public val SUGGESTED_EXTRAS: List<SuggestedPackage> = listOf(
        SuggestedPackage("waybar", "Status bar", "A ready-made bar if you would rather not build Quickshell."),
        SuggestedPackage("mako", "Notifications", "Lightweight Wayland notification daemon."),
        SuggestedPackage("swaybg", "Wallpaper", "Sets the Omarchy wallpaper on the background layer."),
        SuggestedPackage("imv", "Image viewer", "Minimal Wayland image viewer."),
        SuggestedPackage("mpv", "Video player", "Plays video through the software renderer."),
        SuggestedPackage("python", "Python 3", "For scripting and for building widgets."),
        SuggestedPackage("nodejs", "Node.js", "Runtime for JavaScript tooling."),
        SuggestedPackage("npm", "npm", "Node package manager."),
        SuggestedPackage("go", "Go", "The Go toolchain."),
        SuggestedPackage("rustup", "Rust", "Rust toolchain installer."),
        SuggestedPackage("gcc", "GCC", "C and C++ compiler."),
        SuggestedPackage("python-pip", "pip", "Python package installer."),
        SuggestedPackage("firefox", "Firefox", "Large, but it runs. Expect slow software rendering."),
        SuggestedPackage("thunar", "File manager", "GTK file manager."),
        SuggestedPackage("nautilus", "Files (GNOME)", "GTK file manager with search."),
        SuggestedPackage("gimp", "GIMP", "Image editor."),
        SuggestedPackage("inkscape", "Inkscape", "Vector editor."),
        SuggestedPackage("libreoffice-still", "LibreOffice", "Office suite; a big download."),
        SuggestedPackage("xorg-xwayland", "XWayland", "Needed by X11-only apps (not yet wired up)."),
        SuggestedPackage("tmux", "tmux", "Terminal multiplexer."),
        SuggestedPackage("zsh", "zsh", "Alternative shell."),
        SuggestedPackage("htop", "htop", "Process viewer."),
        SuggestedPackage("ncdu", "ncdu", "Find what is using your storage."),
    )
}

/** A package the app offers to install, with a human explanation. */
public data class SuggestedPackage(
    val name: String,
    val title: String,
    val description: String,
)
